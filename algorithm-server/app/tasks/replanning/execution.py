"""Bound the complete native planning job, including initialization and audit.

The native optimizer's per-run timer does not cover feasible-start search or
the final network evaluation. A disposable process gives the service a real
deadline without leaving a timed-out numerical thread consuming CPU.
"""
import json
import logging
import os
from pathlib import Path
import queue
import signal
import subprocess
import sys
import threading
import time

_slots = threading.BoundedSemaphore(max(1, int(os.getenv('DRILLING_PLANNING_WORKERS', '2'))))
_worker_count = max(1, int(os.getenv('DRILLING_PLANNING_WORKERS', '2')))


class _PersistentWorker:
    """One resident numerical worker with kill-and-replace timeout handling."""

    def __init__(self):
        self._lock = threading.Lock()
        self._process = None
        self._start()

    @staticmethod
    def _environment():
        return {**os.environ, 'OMP_NUM_THREADS': '1', 'OPENBLAS_NUM_THREADS': '1',
                'MKL_NUM_THREADS': '1', 'PYTHONFAULTHANDLER': '1'}

    def _start(self):
        working_directory = str(Path(__file__).resolve().parents[3])
        command = [sys.executable, '-m', 'app.tasks.replanning.persistent_worker']
        if os.name == 'posix':
            bootstrap = ('import os,sys; os.setsid(); os.chdir(sys.argv[1]); '
                         'os.execvpe(sys.argv[2],sys.argv[2:],os.environ)')
            command = [sys.executable, '-c', bootstrap, working_directory, *command]
        self._process = subprocess.Popen(
            command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True, bufsize=1,
            cwd=None if os.name == 'posix' else working_directory,
            env=self._environment(), close_fds=os.name != 'posix')

    def _stop(self):
        process = self._process
        self._process = None
        if process is None:
            return
        if process.poll() is None:
            try:
                if os.name == 'posix':
                    os.killpg(process.pid, signal.SIGKILL)
                else:
                    process.kill()
            except ProcessLookupError:
                pass
        try:
            process.communicate(timeout=1)
        except Exception:
            pass

    def restart(self):
        self._stop()
        self._start()

    def run(self, payload, seconds):
        with self._lock:
            if self._process is None or self._process.poll() is not None:
                self._start()
            response_queue = queue.Queue(maxsize=1)

            def read_response():
                try:
                    line = self._process.stdout.readline()
                    response_queue.put(line if line else None)
                except Exception:
                    response_queue.put(None)

            reader = threading.Thread(target=read_response, name='planning-worker-reader', daemon=True)
            reader.start()
            try:
                self._process.stdin.write(json.dumps(payload, allow_nan=False) + '\n')
                self._process.stdin.flush()
                line = response_queue.get(timeout=seconds)
            except queue.Empty as exc:
                self.restart()
                raise PlanningDeadline(
                    f'再规划已达到 {seconds:g} 秒最大等待时间，计算已终止；算法未在等待时间内返回结果。') from exc
            except (BrokenPipeError, OSError) as exc:
                self.restart()
                raise PlanningWorkerFailed('再规划计算进程异常退出，任务已结束。请检查算法服务日志后重试。') from exc
            if not line:
                self.restart()
                raise PlanningWorkerFailed('再规划计算进程异常退出，任务已结束。请检查算法服务日志后重试。')
            try:
                response = json.loads(line)
            except (ValueError, TypeError) as exc:
                self.restart()
                raise PlanningWorkerFailed('再规划计算进程返回了无效结果，任务已结束。') from exc
            if response.get('error'):
                raise ValueError(response['error'])
            result = response['result']
            result.setdefault('timing', {}).update(response.get('worker_timing') or {})
            return result


class _PersistentWorkerPool:
    def __init__(self, count):
        self._count = count
        self._lock = threading.Lock()
        self._workers = None
        self._available = queue.Queue()

    def warm(self):
        with self._lock:
            if self._workers is not None:
                return
            self._workers = [_PersistentWorker() for _ in range(self._count)]
            for worker in self._workers:
                self._available.put(worker)

    def run(self, payload, seconds):
        self.warm()
        try:
            worker = self._available.get(timeout=max(0.1, seconds))
        except queue.Empty as exc:
            raise PlanningBusy('再规划计算资源正忙，请稍后重试') from exc
        try:
            return worker.run(payload, seconds)
        finally:
            self._available.put(worker)

    def close(self):
        with self._lock:
            workers, self._workers = self._workers, None
        for worker in workers or []:
            with worker._lock:
                worker._stop()


_persistent_workers = _PersistentWorkerPool(_worker_count)


class PlanningBusy(RuntimeError):
    pass


class PlanningDeadline(RuntimeError):
    pass


class PlanningWorkerFailed(RuntimeError):
    pass


def _run_worker(payload, seconds, command=None):
    # Tests and explicit commands retain the disposable-process behavior. The
    # production path reuses a prewarmed process to remove import/startup cost.
    if command is None:
        return _persistent_workers.run(payload, seconds)
    env = {**os.environ, 'OMP_NUM_THREADS': '1', 'OPENBLAS_NUM_THREADS': '1', 'MKL_NUM_THREADS': '1',
           'PYTHONFAULTHANDLER': '1'}
    command=command or [sys.executable, '-m', 'app.tasks.replanning.worker']
    working_directory=str(Path(__file__).resolve().parents[3])
    if os.name=='posix':
        # Popen(cwd=..., start_new_session=True) falls back to fork_exec.
        # Forking the threaded numerical server can crash on macOS before
        # exec. Keep Popen eligible for posix_spawn; establish the process
        # group and working directory in the new, single-threaded interpreter.
        bootstrap='import os,sys; os.setsid(); os.chdir(sys.argv[1]); os.execvpe(sys.argv[2],sys.argv[2:],os.environ)'
        command=[sys.executable,'-c',bootstrap,working_directory,*command]
    process = subprocess.Popen(command,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        cwd=None if os.name=='posix' else working_directory, env=env,
        close_fds=os.name!='posix')
    try:
        output, diagnostic = process.communicate(json.dumps(payload, allow_nan=False), timeout=seconds)
    except BaseException as exc:
        if process.poll() is None:
            if os.name == 'posix':
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    # The deadline may expire before bootstrap calls setsid.
                    process.kill()
            else:
                process.kill()
        process.communicate()
        if isinstance(exc, subprocess.TimeoutExpired):
            raise PlanningDeadline(f'再规划已达到 {seconds:g} 秒最大等待时间，计算已终止；算法未在等待时间内返回结果。') from exc
        raise
    if process.returncode != 0:
        logging.getLogger(__name__).error('Planning worker exit %s: %s',process.returncode,diagnostic[-6000:])
        raise PlanningWorkerFailed('再规划计算进程异常退出，任务已结束。请检查算法服务日志后重试。')
    try:
        response = json.loads(output)
    except (ValueError, TypeError) as exc:
        raise PlanningWorkerFailed('再规划计算进程返回了无效结果，任务已结束。') from exc
    if response.get('error'):
        raise ValueError(response['error'])
    result = response['result']
    result.setdefault('timing', {}).update(response.get('worker_timing') or {})
    return result


def execute_planning(payload):
    if not _slots.acquire(blocking=False):
        raise PlanningBusy('再规划计算资源正忙，请稍后重试')
    try:
        start = time.monotonic()
        try:
            result = _run_worker(payload.model_dump(), payload.execution_timeout_seconds)
        except PlanningDeadline as exc:
            # The worker is intentionally killed at the configured outer
            # patience boundary.
            # Return a structured terminal state so the gateway and UI can
            # distinguish a patience timeout from a transport failure.
            elapsed = time.monotonic() - start
            result = {
                'status': 'TIMEOUT',
                'solver': payload.solver,
                'solver_converged': False,
                'feasible_solution': False,
                'stop_reason': 'MAX_WAIT_SECONDS',
                'iteration_limit_reached': False,
                'function_evaluation_limit_reached': False,
                'time_limit_reached': True,
                'max_wait_seconds': payload.execution_timeout_seconds,
                'total_length': None,
                'terminal_error': None,
                'target_error': None,
                'iterations': 0,
                'constraint_violations': [str(exc)],
                'trajectory': [],
                'segments': [],
                'collision_evaluation': None,
                'solver_metadata': {
                    'actual_solver': payload.solver,
                    'engine': 'well_control_iscwsa',
                    'report': {'status': 'max_wait_seconds'},
                    'constraint_audit': {'feasible': False},
                },
                'failure_reason': 'MAX_WAIT_SECONDS',
                'timing': {
                    'algorithm_total_seconds': elapsed,
                    'total_seconds': elapsed,
                },
            }
        else:
            elapsed = time.monotonic() - start
        result['execution'] = {
            'timeout_seconds': payload.execution_timeout_seconds,
            'elapsed_seconds': elapsed,
            'execution_total_seconds': elapsed,
            'scope': '算法进程启动、可行解构造、优化、最终核算、防碰复核及结果返回',
        }
        result.setdefault('timing', {})['execution_total_seconds'] = elapsed
        return result
    finally:
        _slots.release()


def warm_planning_workers():
    """Preload numerical modules during service startup, outside request time."""
    _persistent_workers.warm()


def close_planning_workers():
    _persistent_workers.close()

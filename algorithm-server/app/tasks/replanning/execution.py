"""Bound the complete native planning job, including initialization and audit.

The native optimizer's per-run timer does not cover feasible-start search or
the final network evaluation. A disposable process gives the service a real
deadline without leaving a timed-out numerical thread consuming CPU.
"""
import json
import logging
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time

_slots = threading.BoundedSemaphore(max(1, int(os.getenv('DRILLING_PLANNING_WORKERS', '2'))))


class PlanningBusy(RuntimeError):
    pass


class PlanningDeadline(RuntimeError):
    pass


class PlanningWorkerFailed(RuntimeError):
    pass


def _run_worker(payload, seconds, command=None):
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
            raise PlanningDeadline(f'再规划已达到 {seconds:g} 秒任务时限，计算已终止。请检查目标距离、段长和曲率约束，或调整任务时限后重试。') from exc
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
    return response['result']


def execute_planning(payload):
    if not _slots.acquire(blocking=False):
        raise PlanningBusy('再规划计算资源正忙，请稍后重试')
    try:
        start = time.monotonic()
        result = _run_worker(payload.model_dump(), payload.execution_timeout_seconds)
        result['execution'] = {'timeout_seconds': payload.execution_timeout_seconds,
                               'elapsed_seconds': time.monotonic() - start,
                               'scope': '初始化、求解、密集复核及防碰结果'}
        return result
    finally:
        _slots.release()

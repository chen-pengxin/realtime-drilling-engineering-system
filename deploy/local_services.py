#!/usr/bin/env python3
"""Start/stop only this checkout's three application services.

MYSQL_PASSWORD (or SPRING_DATASOURCE_DRUID_MASTER_PASSWORD) is supplied by the
operator/environment. MySQL itself is managed by the local database service.
"""
import argparse,getpass,hashlib,json,os,secrets,shutil,signal,socket,subprocess,sys,time,urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
RUNTIME=ROOT/'.local-runtime'
STATE=RUNTIME/'services.json'

def stage_java_jar(source):
    """Keep running class loaders independent of Maven's mutable target JAR."""
    digest=hashlib.sha256()
    with source.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
    directory=RUNTIME/'java-builds'/digest.hexdigest()
    target=directory/'ruoyi-admin.jar'
    if not target.exists():
        directory.mkdir(parents=True,exist_ok=True)
        temporary=directory/('ruoyi-admin.'+str(os.getpid())+'.tmp')
        try:
            shutil.copyfile(source,temporary)
            temporary.chmod(0o444)
            os.replace(temporary,target)
        finally:
            temporary.unlink(missing_ok=True)
    return target

def ready(name,url):
    path={'algorithm':'/health','java':'/captcha/captchaImage?type=math','frontend':'/login'}[name]
    try:
        with urllib.request.urlopen(url+path,timeout=3) as response:
            if response.status!=200:return False
            if name=='algorithm':return json.load(response).get('status')=='UP'
            if name=='java':return response.headers.get_content_type()=='image/jpeg' and response.read(2)==b'\xff\xd8'
            return True
    except (OSError,ValueError):
        return False

def port_open(port):
    with socket.socket() as s:
        s.settimeout(.4)
        return s.connect_ex(('127.0.0.1',port))==0

def matches(pid,signature):
    r=subprocess.run(['ps','-p',str(pid),'-o','command='],capture_output=True,text=True)
    return r.returncode==0 and signature in r.stdout

def process_start(pid):
    r=subprocess.run(['ps','-p',str(pid),'-o','lstart='],capture_output=True,text=True)
    return r.stdout.strip() if r.returncode==0 else ''

def same_process(row):
    return matches(row['pid'],row['signature']) and (
        not row.get('startedAt') or process_start(row['pid'])==row['startedAt'])

def records():
    try:return json.loads(STATE.read_text())
    except (FileNotFoundError,ValueError):return {}

def stop():
    state=records()
    for name in ('frontend','java','algorithm'):
        row=state.get(name)
        if not row:continue
        if not same_process(row):
            print(name+': original process already stopped; no unrelated process killed')
            continue
        try:os.killpg(row['pid'],signal.SIGTERM)
        except ProcessLookupError:continue
        deadline=time.monotonic()+12
        while same_process(row) and time.monotonic()<deadline:time.sleep(.2)
        if same_process(row):
            print(name+': did not stop in 12 seconds; inspect PID '+str(row['pid']))
        else:print(name+': stopped')
    remaining={k:v for k,v in state.items() if same_process(v)}
    STATE.write_text(json.dumps(remaining,indent=2))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['start','stop','restart','status'])
    parser.add_argument('--build',action='store_true',help='rebuild Java and Vue before starting')
    args=parser.parse_args();RUNTIME.mkdir(exist_ok=True)
    if args.action=='status':
        state=records()
        for name in ('algorithm','java','frontend'):
            row=state.get(name);alive=bool(row and same_process(row))
            print(f'{name}: '+('RUNNING '+row['url'] if alive else 'STOPPED'))
        return
    if args.action in ('stop','restart'):stop()
    if args.action=='stop':return
    if any(same_process(v) for v in records().values()):
        raise SystemExit('This checkout already has managed services. Use status or restart.')
    ports={'algorithm':int(os.getenv('ALGORITHM_PORT','8000')),'java':int(os.getenv('JAVA_PORT','8080')),'frontend':int(os.getenv('FRONTEND_PORT','5173'))}
    for name,port in ports.items():
        if port_open(port):raise SystemExit(f'{name} port {port} is occupied. Resolve the existing process or choose another port; it will not be killed automatically.')
    mysql_host=os.getenv('MYSQL_HOST','127.0.0.1');mysql_port=int(os.getenv('MYSQL_PORT','3306'))
    try:
        with socket.create_connection((mysql_host,mysql_port),timeout=3):pass
    except OSError:raise SystemExit('MySQL is unavailable; start the database service first.')
    algorithm=ROOT/'algorithm-server';python=algorithm/'.venv/bin/python'
    if not python.exists():raise SystemExit('Create algorithm-server/.venv and install requirements-dev.txt first; see README.')
    jar=ROOT/'ruoyi-java/ruoyi-admin/target/ruoyi-admin.jar'
    reader=algorithm/'tools/legacy-office-reader/target/legacy-office-reader.jar'
    if args.build or not reader.exists():
        subprocess.run(['mvn','-q','package'],cwd=algorithm/'tools/legacy-office-reader',check=True)
    if args.build:
        subprocess.run(['mvn','-q','-pl','ruoyi-admin','-am','package','-DskipTests=false'],cwd=ROOT/'ruoyi-java',check=True)
        subprocess.run(['npm','run','build:prod'],cwd=ROOT/'ruoyi-vue3',check=True)
    if not jar.exists():raise SystemExit('Java jar missing; run start --build.')
    env=dict(os.environ)
    password=env.get('SPRING_DATASOURCE_DRUID_MASTER_PASSWORD') or env.get('MYSQL_PASSWORD')
    if not password:
        if not sys.stdin.isatty():raise SystemExit('Set MYSQL_PASSWORD or SPRING_DATASOURCE_DRUID_MASTER_PASSWORD.')
        password=getpass.getpass('MySQL password (not saved): ')
    database=env.get('MYSQL_DATABASE','ry')
    if not database.replace('_','').isalnum():raise SystemExit('MYSQL_DATABASE must contain letters, digits or underscores.')
    profile=Path(env.get('RUOYI_PROFILE',str(Path.home()/'.ruoyi/uploadPath'))).expanduser().resolve();profile.mkdir(parents=True,exist_ok=True)
    token=env.get('DRILLING_CALLBACK_TOKEN')
    if not token:
        secret=RUNTIME/'callback-token'
        if not secret.exists():
            fd=os.open(secret,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            with os.fdopen(fd,'w') as stream:stream.write(secrets.token_urlsafe(32))
        token=secret.read_text().strip()
    env.update(SPRING_DATASOURCE_DRUID_MASTER_PASSWORD=password,SPRING_DATASOURCE_DRUID_MASTER_USERNAME=env.get('MYSQL_USER','root'),SPRING_DATASOURCE_DRUID_MASTER_URL=f'jdbc:mysql://{mysql_host}:{mysql_port}/{database}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=GMT%2B8',DRILLING_ALGORITHM_URL=f'http://127.0.0.1:{ports["algorithm"]}',RUOYI_PROFILE=str(profile),TRAIN_DATA_ROOT=str(profile),TRAIN_TEMPLATE_ROOT=str(profile),VITE_APP_PROXY_TARGET=f'http://127.0.0.1:{ports["java"]}')
    env.update(DRILLING_CALLBACK_TOKEN=token,TASK_CALLBACK_TOKEN=token,TASK_CALLBACK_URL=f'http://127.0.0.1:{ports["java"]}/api/train/task/callback',TRAINING_STATE_FILE=str(RUNTIME/'training-tasks.json'),MODEL_ROOT=str(RUNTIME/'models'),VITE_OPEN_BROWSER='false')
    jar=stage_java_jar(jar)
    commands=[('algorithm',[str(python),'-m','uvicorn','app.main:app','--host','127.0.0.1','--port',str(ports['algorithm'])],algorithm,'uvicorn app.main:app'),('java',['java','-jar',str(jar),'--server.port='+str(ports['java'])],ROOT/'ruoyi-java',str(jar)),('frontend',['npm','run','dev','--','--host','127.0.0.1','--port',str(ports['frontend']),'--strictPort'],ROOT/'ruoyi-vue3','vite')]
    state={}
    try:
        for name,command,cwd,signature in commands:
            with (RUNTIME/(name+'.log')).open('a') as log:p=subprocess.Popen(command,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            # npm's command line is 'npm run dev ...'; retain its actual command
            # as the PID identity, while its whole process group handles Vite.
            if name=='frontend':signature='npm run dev'
            state[name]={'pid':p.pid,'signature':signature,'startedAt':process_start(p.pid),'url':f'http://127.0.0.1:{ports[name]}','log':str(RUNTIME/(name+'.log'))};STATE.write_text(json.dumps(state,indent=2))
            deadline=time.monotonic()+90
            while True:
                if p.poll() is not None:raise RuntimeError(name+' exited. See '+state[name]['log'])
                if port_open(ports[name]) and ready(name,state[name]['url']):break
                if time.monotonic()>deadline:raise RuntimeError(name+' startup timed out. See '+state[name]['log'])
                time.sleep(.5)
            print(name+': '+state[name]['url'],flush=True)
            if name=='java':
                seed_env=dict(env,MYSQL_PASSWORD=password,PYTHONPATH=str(algorithm))
                subprocess.run([str(python),str(algorithm/'tools/import_test_cases.py'),'--profile',str(profile)],cwd=algorithm,env=seed_env,check=True)
        print('Open the frontend URL. Logs and process IDs: '+str(RUNTIME))
    except BaseException:
        stop();raise

if __name__=='__main__':main()

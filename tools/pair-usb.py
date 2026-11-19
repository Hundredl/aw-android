#!/usr/bin/env python3
"""One-use, loopback-only USB setup. Secrets stay in memory and Android Keystore.
Read {endpoint,site_token,adb,serial} JSON from hidden stdin. Paste the device
credential into the temporary local form using the authenticated website UI.
Only UsbPairing#configure runs; never run the activity-clearing QA suite on a phone.
"""
import json,sys,termios,secrets,subprocess,socket,time,threading
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
from urllib.parse import parse_qs
old=termios.tcgetattr(sys.stdin)
try:
    new=termios.tcgetattr(sys.stdin);new[3]&=~termios.ECHO;termios.tcsetattr(sys.stdin,termios.TCSANOW,new)
    print('Ready for pairing configuration JSON on stdin (hidden).',flush=True)
    cfg=json.loads(sys.stdin.readline())
finally:termios.tcsetattr(sys.stdin,termios.TCSANOW,old)
nonce=secrets.token_hex(32)
adb=[cfg['adb'],'-s',cfg['serial']]
pkg='net.activitywatch.android.ledger.debug'
used=False
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def reply(self,code,body):
        self.send_response(code);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Cache-Control','no-store');self.send_header('Content-Security-Policy',"default-src 'none'; form-action 'self'; frame-ancestors 'none'");self.send_header('Referrer-Policy','same-origin');self.end_headers();self.wfile.write(body.encode())
    def do_GET(self):
        if self.path!='/'+nonce:return self.reply(404,'Not found')
        self.reply(200,f'<h1>Pixel USB 配对</h1><p>只在本机运行，凭据直接加密写入已连接手机，不保存到电脑文件。</p><form method="POST" action="/{nonce}"><label>设备凭据 <input type="password" name="device_token" autocomplete="off" required></label><button>配置手机并启用每小时同步</button></form>')
    def do_POST(self):
        global used
        expected=f'http://127.0.0.1:{self.server.server_port}'
        if used or self.path!='/'+nonce or self.headers.get('Origin')!=expected:return self.reply(403,'Rejected')
        length=int(self.headers.get('Content-Length','0'))
        if not 0<length<2048:return self.reply(400,'Invalid size')
        token=parse_qs(self.rfile.read(length).decode()).get('device_token',[''])[0].strip()
        if not 32<=len(token)<=512:return self.reply(400,'Invalid credential')
        used=True
        port=None
        try:
            proc=subprocess.Popen(adb+['shell','am','instrument','-w','-e','class','net.activitywatch.android.ledger.UsbPairing#configure','-e','pair_nonce',nonce,pkg+'.test/androidx.test.runner.AndroidJUnitRunner'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
            r=subprocess.run(adb+['forward','tcp:0','localabstract:ledger-usb-pair-'+nonce],capture_output=True,check=True)
            port=int(r.stdout.strip())
            payload=json.dumps({'nonce':nonce,'endpoint':cfg['endpoint'],'device_token':token,'site_token':cfg['site_token']}).encode()+b'\n'
            result=None
            sent=False
            for _ in range(30):
                try:
                    with socket.create_connection(('127.0.0.1',port),timeout=3) as s:
                        s.settimeout(10)
                        if s.recv(64)!=b'READY\n':raise ConnectionError()
                        sent=True;s.sendall(payload);result=s.recv(1024)
                    if result:break
                except (OSError,ConnectionError):
                    if sent:raise
                time.sleep(1)
            if not result or not json.loads(result).get('configured'):raise RuntimeError('No confirmation')
            output=proc.communicate(timeout=30)[0]
            if b'OK (1 test)' not in output:raise RuntimeError('Setup runner did not complete')
            subprocess.run(adb+['shell','am','start','-W','-n',pkg+'/net.activitywatch.android.ledger.LedgerActivity'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
            print('PHONE_CONFIGURED: encrypted credentials, hourly sync enabled; verify upload separately.',flush=True)
            self.reply(200,'<h1>手机配置已完成</h1><p>已启用每小时自动同步，等待首次上传验证。可以关闭此页。</p>')
        except Exception:
            print('PAIRING_INCOMPLETE: inspect phone configuration before retrying.',flush=True)
            self.reply(500,'<h1>配对未确认</h1><p>请先检查手机状态，不要重复生成凭据。</p>')
        finally:
            if port:subprocess.run(adb+['forward','--remove','tcp:'+str(port)],capture_output=True)
            threading.Thread(target=self.server.shutdown,daemon=True).start()
server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
print(f'PAIRING_URL http://127.0.0.1:{server.server_port}/{nonce}',flush=True)
server.serve_forever();server.server_close()

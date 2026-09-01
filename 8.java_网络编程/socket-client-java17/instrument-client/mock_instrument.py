import socket, threading, time
HOST, PORT='127.0.0.1',5025

def client(c):
    data=b''
    while True:
        x=c.recv(4096)
        if not x: break
        data += x
        while b'\r\n' in data:
            line, data = data.split(b'\r\n',1)
            cmd=line.decode()
            # unsolicited status first, then split response
            c.sendall(b'STATUS:READY\r\n')
            if cmd == 'MEAS:VOLT?':
                resp=b'VOLT:12.345\r\n'
                c.sendall(resp[:5]); time.sleep(.05); c.sendall(resp[5:])
            else:
                c.sendall(b'OK\r\n')
    c.close()

def main():
    s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); s.bind((HOST,PORT)); s.listen(5)
    while True:
        c,_=s.accept(); threading.Thread(target=client,args=(c,),daemon=True).start()
main()

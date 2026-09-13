import socket, threading, time

HOST, PORT = '127.0.0.1', 5025


def handle_line(c):
    buf = b''
    while True:
        try:
            x = c.recv(4096)
        except (ConnectionResetError, OSError):
            break
        if not x:
            break
        buf += x
        while b'\r\n' in buf:
            line, buf = buf.split(b'\r\n', 1)
            cmd = line.decode(errors='replace').strip()
            if not cmd:
                continue

            if cmd.upper() == 'PING':
                c.sendall(b'PONG\r\n')
            elif cmd.upper() == 'MEAS:VOLT?':
                # 模拟拆包：分两次发送
                resp = b'VOLT:12.345\r\n'
                c.sendall(resp[:5])
                time.sleep(0.05)
                c.sendall(resp[5:])
            elif cmd.upper() == 'BYE':
                c.sendall(b'BYE\r\n')
                c.close()
                return
            elif cmd.upper() == 'DISCONNECT':
                c.sendall(b'OK\r\n')
                time.sleep(0.1)
                c.close()
                return
            elif cmd.upper().startswith('MEAS:VOLT? #'):
                # 延迟响应：MEAS:VOLT? #5000 表示延迟 5 秒
                try:
                    delay_ms = int(cmd.split('#')[1].strip())
                    time.sleep(delay_ms / 1000.0)
                except (ValueError, IndexError):
                    pass
                c.sendall(b'VOLT:12.345\r\n')
            elif cmd.upper() == 'STICKY':
                # 粘包：一次发送多帧
                c.sendall(b'STICKY:FRAME1\r\nSTICKY:FRAME2\r\nSTICKY:FRAME3\r\n')
            elif cmd.upper() == 'FRAG':
                # 拆包：逐字节发送
                resp = b'FRAG:COMPLETE_RESPONSE\r\n'
                for i in range(0, len(resp), 2):
                    c.sendall(resp[i:i + 2])
                    time.sleep(0.03)
            elif cmd.upper() == 'EXIT':
                c.sendall(b'SHUTTING_DOWN\r\n')
                c.close()
                return
            else:
                c.sendall(b'OK\r\n')
    c.close()


def main():
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((HOST, PORT))
    s.listen(5)
    print(f'[mock_instrument] Listening on {HOST}:{PORT}')
    while True:
        c, _ = s.accept()
        threading.Thread(target=handle_line, args=(c,), daemon=True).start()


if __name__ == '__main__':
    main()
import { createServer } from 'node:http'
import { mkdtempSync, existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const jar = path.join(root, 'backend/target/signdesk-0.1.0.jar')
if (!existsSync(jar)) throw new Error('Run node scripts/build.mjs before the browser tests')
const directory = mkdtempSync(path.join(tmpdir(), 'signdesk-browser-'))
const received = []
const fixture = createServer(async (request, response) => {
  if (request.url === '/received') { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(received)); return }
  let body = ''
  for await (const chunk of request) body += chunk.toString()
  received.push({ url: request.url, method: request.method, cookie: request.headers.cookie, device: request.headers['x-device'], body })
  const route = new URL(request.url, 'http://127.0.0.1').pathname
  if (route === '/responses/json') {
    response.setHeader('Content-Type', 'application/json; charset=UTF-8')
    response.end(JSON.stringify({ code: 0, message: '响应体 response-fixture-secret', html: '<img src=x onerror="window.__responseExecuted=true">' }, null, 2))
    return
  }
  if (route === '/responses/error') {
    response.writeHead(500, { 'Content-Type': 'text/html; charset=UTF-8' })
    response.end('<div>fixture-error-response</div><img src=x onerror="window.__responseExecuted=true">')
    return
  }
  if (route === '/responses/empty') { response.writeHead(204); response.end(); return }
  if (route === '/responses/binary') {
    response.setHeader('Content-Type', 'application/octet-stream')
    response.end(Buffer.from([0, 1, 2, 255]))
    return
  }
  if (route === '/responses/large') {
    response.setHeader('Content-Type', 'text/plain; charset=UTF-8')
    response.end(Buffer.alloc(1048577, 'L'))
    return
  }
  if (route === '/responses/partial') {
    response.writeHead(200, { 'Content-Type': 'text/plain; charset=UTF-8' })
    response.write('partial-response-fixture-secret')
    const timer = setTimeout(() => response.end('-not-received'), 1500)
    response.on('close', () => clearTimeout(timer))
    return
  }
  response.setHeader('Content-Type', 'application/json'); response.end('{"code":0}')
})
fixture.listen(18081, '127.0.0.1')
const executable = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
const backend = spawn(executable, ['-jar', jar], { env: { ...process.env, SIGNDESK_PORT: '18080', SIGNDESK_BIND_ADDRESS: '127.0.0.1', SIGNDESK_DATA_DIR: path.join(directory, 'data'), SIGNDESK_KEY_FILE: path.join(directory, 'master.key'), SIGNDESK_SECRET_KEY: '', SIGNDESK_ALLOWED_HOSTS: '127.0.0.1' }, stdio: 'inherit' })
let stopped = false
function stop() { if (stopped) return; stopped = true; fixture.close(); backend.kill('SIGTERM') }
process.on('SIGINT', stop); process.on('SIGTERM', stop)
backend.on('exit', code => { stop(); rmSync(directory, { recursive: true, force: true }); process.exit(code || 0) })

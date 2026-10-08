import { spawnSync } from 'node:child_process'
import { cpSync, rmSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const windows = process.platform === 'win32'
function run(command, args, directory) {
  const result = spawnSync(windows ? `${command}.cmd` : command, args, { cwd: path.join(root, directory), stdio: 'inherit', shell: windows })
  if (result.error) { console.error(result.error.message); process.exit(1) }
  if (result.status !== 0) process.exit(result.status || 1)
}
run('npm', ['ci', '--no-audit', '--no-fund'], 'frontend')
run('npm', ['run', 'build'], 'frontend')
const dist = path.join(root, 'frontend/dist')
if (!existsSync(path.join(dist, 'index.html'))) throw new Error('Frontend output missing')
const target = path.join(root, 'backend/src/main/resources/static')
rmSync(target, { recursive: true, force: true })
cpSync(dist, target, { recursive: true })
run('mvn', ['-B', 'clean', 'verify'], 'backend')
console.log('Build complete: backend/target/signdesk-0.1.0.jar')

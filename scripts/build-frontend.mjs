import { spawnSync } from 'node:child_process'
import { cpSync, rmSync, mkdirSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const windows = process.platform === 'win32'
const buildDirectory = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(root, 'backend/target/jib')
const target = path.join(buildDirectory, 'frontend-resources/static')
const workspace = path.join(buildDirectory, 'frontend-build')

function run(args) {
  const result = spawnSync(windows ? 'npm.cmd' : 'npm', args, {
    cwd: workspace, stdio: 'inherit', shell: windows,
  })
  if (result.error) { console.error(result.error.message); process.exit(1) }
  if (result.status !== 0) process.exit(result.status || 1)
}

// Build in target so npm ci cannot remove dependencies used by a running Vite server.
// Copy only build inputs, not .env files, old outputs or user data.
rmSync(target, { recursive: true, force: true })
rmSync(workspace, { recursive: true, force: true })
mkdirSync(workspace, { recursive: true })
for (const name of ['package.json', 'package-lock.json', 'index.html', 'vite.config.js', 'src', 'tests']) {
  cpSync(path.join(root, 'frontend', name), path.join(workspace, name), { recursive: true })
}
const publicDir = path.join(root, 'frontend/public')
if (existsSync(publicDir)) cpSync(publicDir, path.join(workspace, 'public'), { recursive: true })
run(['ci', '--no-audit', '--no-fund'])
run(['run', 'test:unit'])
run(['run', 'build'])
const dist = path.join(workspace, 'dist')
if (!existsSync(path.join(dist, 'index.html'))) throw new Error('Frontend output missing')
cpSync(dist, target, { recursive: true })
console.log(`Frontend ready: ${target}`)

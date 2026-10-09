export const BACKUP_FORMAT = 'signdesk-plain-v1'
export const MAX_BACKUP_FILE_BYTES = 12_582_912

/**
 * @typedef {object} BackupPayload
 * @property {boolean} includesRequests
 * @property {Record<string, unknown>} settings
 * @property {Array<Record<string, unknown>>} platforms
 * @property {Array<Record<string, unknown>>} accounts
 * @property {Array<Record<string, unknown>>} requests
 * @property {Array<Record<string, unknown>>} templates
 * @property {Array<Record<string, unknown>>} schedules
 * @property {Array<Record<string, unknown>>} completed
 * @property {Array<Record<string, unknown>>} pending
 */

/**
 * @typedef {object} BackupFile
 * @property {string} format
 * @property {BackupPayload} payload
 */

/**
 * 先限制实际文件大小，再核对 UTF-8 字节数；完整结构与业务关系由服务端预览校验。
 * @param {Pick<File, 'size' | 'text'>} file 用户主动选择的本地 JSON 文件
 * @returns {Promise<BackupFile>} 仅在内存中保留的新格式配置
 */
export async function readBackupFile(file) {
  if (file.size > MAX_BACKUP_FILE_BYTES) throw new Error('文件超过 12 MiB（12,582,912 字节）')
  const text = await file.text()
  if (new TextEncoder().encode(text).byteLength > MAX_BACKUP_FILE_BYTES)
    throw new Error('文件超过 12 MiB（12,582,912 UTF-8 字节）')
  let backup
  try {
    backup = JSON.parse(text)
  } catch {
    throw new Error('请选择合法 JSON 备份文件')
  }
  if (backup?.format !== BACKUP_FORMAT)
    throw new Error('不支持此备份格式，仅接受 signdesk-plain-v1 明文 JSON 文件')
  return backup
}

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ensureSession, login } from '../auth'
import { safeReturnPath } from '../authState.js'
import DeskIcon from '../components/DeskIcon.vue'

const route = useRoute(), router = useRouter()
const username = ref(''), password = ref(''), showPassword = ref(false), busy = ref(false), error = ref('')
let disposed = false
onMounted(async () => {
  try { await ensureSession() }
  catch { if (!disposed) error.value = '暂时无法连接服务，请确认后端运行后重试登录。' }
})
onUnmounted(() => { disposed = true; username.value = ''; password.value = '' })
async function submit() {
  if (busy.value) return
  error.value = ''
  if (!username.value.trim() || !password.value) { error.value = '请输入用户名和密码'; return }
  busy.value = true
  try {
    await login(username.value, password.value)
    password.value = ''
    if (!disposed) await router.replace(safeReturnPath(route.query.redirect))
  } catch (e) {
    if (!disposed) {
      error.value = e.status ? e.message : '暂时无法连接服务，请确认后端运行后重试登录。'
      password.value = ''
    }
  } finally { if (!disposed) busy.value = false }
}
</script>

<template>
  <main class="login-page">
    <div class="login-frame">
      <section class="login-story" aria-label="签行 SignDesk">
        <div class="login-brand"><span class="brand-symbol"><DeskIcon name="check" /></span><span>签行<small>SignDesk</small></span></div>
        <div class="login-story-copy"><span class="login-eyebrow">A LITTLE ORDER, EVERY DAY.</span><h2>你的日常，<br>有序发生。</h2><p>把重复的事交给签行，<br>把时间留给自己。</p></div>
        <div class="login-seal" aria-hidden="true"><DeskIcon name="check" /><span>一日一签 · 从容向前</span></div>
        <p class="login-story-footer">个人请求与签到管理 <span>PRIVATE WORKSPACE</span></p>
      </section>
      <section class="login-panel" aria-labelledby="login-title">
        <div class="login-panel-copy"><span class="login-eyebrow">WELCOME BACK</span><h1 id="login-title">登录签行</h1><p>登录你的工作区，继续今天的安排。</p></div>
        <form class="login-form" @submit.prevent="submit">
          <div class="login-field"><label for="login-username">用户名</label><div class="login-input"><DeskIcon name="user" /><input id="login-username" v-model="username" name="username" autocomplete="username" placeholder="请输入用户名" :maxlength="64" :disabled="busy" required autofocus /></div></div>
          <div class="login-field"><label for="login-password">密码</label><div class="login-input"><DeskIcon name="lock" /><input id="login-password" v-model="password" name="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" :maxlength="256" :disabled="busy" required /><button type="button" class="login-password-toggle" :aria-label="showPassword ? '隐藏密码' : '显示密码'" :aria-pressed="showPassword" :disabled="busy" @click="showPassword = !showPassword">{{ showPassword ? '隐藏' : '显示' }}</button></div></div>
          <p v-if="error" class="login-error" role="alert"><DeskIcon name="alert" />{{ error }}</p>
          <p v-else-if="route.query.expired === '1'" class="login-expired" role="status">登录已过期，请重新登录。</p>
          <button class="login-submit" type="submit" :disabled="busy" :aria-busy="busy"><span>{{ busy ? '正在登录…' : '登录' }}</span><DeskIcon :name="busy ? 'clock' : 'arrow'" /></button>
        </form>
        <p class="login-note"><DeskIcon name="lock" />仅供管理员访问 · 账号由服务配置</p>
        <div class="login-panel-footer"><span>SignDesk</span><span>简单一点，稳妥一点。</span></div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.login-page { min-height: 100svh; display: grid; place-items: center; padding: 48px 28px; background: var(--paper); }
.login-frame { width: min(980px, 100%); min-height: 610px; display: grid; grid-template-columns: 1fr 1fr; border: 1px solid var(--line); border-radius: 18px; overflow: hidden; box-shadow: 0 24px 65px #173b310a; }
.login-story { position: relative; display: flex; flex-direction: column; padding: 42px 44px 27px; background: var(--forest-deep); color: var(--lime); overflow: hidden; }
.login-brand { display: flex; align-items: center; gap: 12px; font-size: 22px; letter-spacing: 4px; }
.login-brand .brand-symbol { width: 40px; height: 40px; margin: 0; border-radius: 12px 12px 12px 3px; }
.login-brand small { display: block; font: 10px var(--mono); letter-spacing: 1px; color: #c1d0b7; margin-top: 1px; }
.login-eyebrow { font: 10px var(--mono); letter-spacing: 1.6px; }
.login-story-copy { position: relative; z-index: 1; margin: 78px 0 40px; }
.login-story-copy > span { color: #a3b89d; font-size: 9px; }
.login-story h2 { font-family: 'Noto Serif SC', 'Songti SC', SimSun, serif; font-size: clamp(35px, 4vw, 44px); line-height: 1.6; font-weight: 400; letter-spacing: 3px; margin-top: 20px; }
.login-story-copy p { margin-top: 22px; font-size: 12px; color: #b0c2ab; line-height: 2; }
.login-seal { position: absolute; bottom: 100px; right: -28px; width: 170px; height: 170px; border: 1px solid #dcebb526; border-radius: 50%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 15px; transform: rotate(-15deg); color: #dcebb547; }
.login-seal::before { content: ''; position: absolute; inset: 12px; border: 1px dashed #dcebb51f; border-radius: 50%; }
.login-seal .icon { width: 50px; height: 50px; stroke-width: 1; }
.login-seal span { font-size: 9px; letter-spacing: 2px; }
.login-story-footer { position: relative; margin-top: auto; padding-top: 23px; border-top: 1px solid #dcebb51c; display: flex; justify-content: space-between; gap: 8px; color: #a3b89d; font-size: 10px; }
.login-story-footer span { font: 8px var(--mono); letter-spacing: .5px; }
.login-panel { display: flex; flex-direction: column; padding: 82px 52px 27px; background: var(--white); }
.login-panel-copy > span { color: #7d8e71; }
.login-panel h1 { font-size: 27px; margin: 14px 0 9px; letter-spacing: 1px; }
.login-panel-copy p { font-size: 12px; color: var(--muted); }
.login-form { margin-top: 32px; }
.login-field { margin-bottom: 23px; }
.login-field label { display: block; font-size: 12px; margin-bottom: 9px; }
.login-input { display: flex; align-items: center; gap: 11px; padding: 0 13px; min-height: 48px; border: 1px solid var(--line-strong); border-radius: 8px; background: #fcfdf9; }
.login-input:focus-within { border-color: var(--forest); box-shadow: 0 0 0 3px var(--wash); }
.login-input > .icon { width: 16px; height: 16px; color: #7d8e71; }
.login-input input { width: 100%; min-width: 0; border: 0; outline: none; background: transparent; color: var(--ink); padding: 13px 0; font-size: 13px; }
.login-input input:focus-visible { outline: none; }
.login-input input::placeholder { color: #96a08e; font-size: 12px; }
.login-password-toggle { flex-shrink: 0; border: 0; background: transparent; font-size: 11px; padding: 8px 0; color: var(--muted); }
.login-error, .login-expired { display: flex; gap: 7px; align-items: flex-start; font-size: 12px; margin: -3px 0 17px; color: var(--red); }
.login-error .icon { width: 15px; height: 15px; margin-top: 2px; }
.login-expired { color: var(--amber); }
.login-submit { width: 100%; min-height: 48px; display: flex; align-items: center; justify-content: space-between; border: 0; border-radius: 8px; background: var(--forest); color: white; padding: 12px 18px; font-size: 14px; transition: background .16s; }
.login-submit:hover { background: var(--forest-deep); }
.login-submit:disabled { opacity: .7; cursor: wait; }
.login-note { display: flex; gap: 6px; justify-content: center; align-items: center; font-size: 10px; color: #829077; margin-top: 18px; }
.login-note .icon { width: 12px; height: 12px; }
.login-panel-footer { margin-top: auto; display: flex; justify-content: space-between; gap: 10px; padding-top: 42px; font-size: 10px; color: #829077; }
.login-panel-footer > span:first-child { font: 10px var(--mono); }
@media (max-width: 730px) {
  .login-page { padding: 24px 16px; }
  .login-frame { max-width: 460px; min-height: auto; grid-template-columns: 1fr; border-radius: 14px; }
  .login-story { padding: 23px 27px; }
  .login-brand { font-size: 20px; }
  .login-story-copy { margin: 20px 0 0; }
  .login-story-copy > span, .login-story-copy p, .login-story-footer, .login-seal { display: none; }
  .login-story h2 { font-size: 24px; margin: 0; letter-spacing: 2px; }
  .login-story h2 br { display: none; }
  .login-panel { padding: 30px 27px 23px; }
  .login-panel h1 { font-size: 23px; margin-top: 10px; }
  .login-form { margin-top: 25px; }
  .login-field { margin-bottom: 20px; }
  .login-panel-footer { padding-top: 32px; }
}
</style>

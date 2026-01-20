<template>
  <header class="tf-header">
    <div class="tf-header__inner">
      <!-- Logo -->
      <router-link to="/index" class="tf-header__logo">
        <img :src="logo" alt="TicketFlow" />
      </router-link>

      <!-- 导航 -->
      <nav class="tf-header__nav" v-if="isShowHeader">
        <router-link to="/index" class="tf-header__nav-item">首页</router-link>
        <router-link to="/allType/index" class="tf-header__nav-item">分类</router-link>
      </nav>

      <!-- 城市选择 -->
      <div class="tf-header__city" v-if="isShowHeader" ref="cityRef">
        <span class="tf-header__city-btn" @click="toggleCityPanel">
          <el-icon :size="14"><Location /></el-icon>
          {{ localName || '选择城市' }}
          <el-icon :size="11" :class="{ 'rotate-180': cityPanelOpen }"><CaretBottom /></el-icon>
        </span>

        <!-- 城市选择浮层（自定义，不使用 el-popover 避免兼容问题） -->
        <div v-if="cityPanelOpen" class="city-dropdown" @click.stop>
          <!-- 顶部：当前城市 + 搜索 -->
          <div class="city-dropdown__header">
            <div class="city-dropdown__current">
              当前：<span class="city-current-badge">{{ localName || '未选择' }}</span>
            </div>
            <el-input
              v-model="citySearchKey"
              placeholder="搜索城市名称..."
              clearable
              size="small"
              :prefix-icon="Search"
              style="width: 180px;"
            />
          </div>

          <!-- 搜索模式：展示搜索结果 -->
          <div v-if="citySearchKey.trim()" class="city-dropdown__body">
            <div v-if="searchedCities.length" class="city-grid">
              <span
                v-for="item in searchedCities"
                :key="item.id"
                class="city-grid-item"
                :class="{ 'city-grid-item--active': item.name === localName }"
                @click="selectCity(item)"
              >{{ item.name }}</span>
            </div>
            <div v-else class="city-empty">暂无匹配城市</div>
          </div>

          <!-- 非搜索模式：热门城市 + 字母分组 -->
          <template v-else>
            <!-- 热门城市 -->
            <div class="city-dropdown__section">
              <div class="city-section-title">热门城市</div>
              <div class="city-hot-chips">
                <span
                  v-for="item in hotCityDedup"
                  :key="item.id"
                  class="city-hot-chip"
                  :class="{ 'city-hot-chip--active': normalizeCityName(item.name) === normalizeCityName(localName) }"
                  @click="selectCity(item)"
                >{{ item.name }}</span>
              </div>
            </div>

            <!-- 字母分组 -->
            <div class="city-dropdown__section">
              <div class="city-section-title-row">
                <span class="city-section-title">更多城市</span>
                <div class="city-letter-tabs">
                  <span
                    v-for="tab in letterTabs"
                    :key="tab.key"
                    class="city-letter-tab"
                    :class="{ 'city-letter-tab--active': cityTabActive === tab.key }"
                    @click.stop="cityTabActive = tab.key"
                  >{{ tab.label }}</span>
                </div>
              </div>
              <div v-if="groupedCities.length" class="city-grid">
                <span
                  v-for="item in groupedCities"
                  :key="item.id"
                  class="city-grid-item"
                  :class="{ 'city-grid-item--active': normalizeCityName(item.name) === normalizeCityName(localName) }"
                  @click="selectCity(item)"
                >{{ item.name }}</span>
              </div>
              <div v-else class="city-empty">该分组暂无城市</div>
            </div>
          </template>
        </div>
      </div>

      <!-- 搜索框 -->
      <div class="tf-header__search" v-if="isShowHeader">
        <div class="tf-search-box" :class="{ 'tf-search-box--focused': searchFocused }">
          <el-icon class="tf-search-box__icon"><Search /></el-icon>
          <input
            v-model="iptSearch"
            class="tf-search-box__input"
            placeholder="搜索演出、明星、场馆..."
            @focus="searchFocused = true"
            @blur="searchFocused = false"
            @keyup.enter="getProgramSearchList"
          />
          <button class="tf-search-box__btn" @click="getProgramSearchList">搜索</button>
        </div>
      </div>

      <!-- 亮暗主题切换组件 -->
      <div class="tf-header__theme-toggle" v-if="isShowHeader" @click="toggleTheme" title="切换暗黑/亮色主题">
        <span class="theme-btn" :class="{ 'theme-btn--dark': isDark }">
          <el-icon :size="14" v-if="isDark"><Moon /></el-icon>
          <el-icon :size="14" v-else><Sunny /></el-icon>
          <span>{{ isDark ? '暗黑' : '亮色' }}</span>
        </span>
      </div>

      <!-- 右侧用户区 -->
      <div class="tf-header__user" v-if="isShowHeader">
        <!-- 已登录：头像 + 下拉菜单 -->
        <template v-if="isHasToken">
          <el-popover :width="150" placement="bottom-end" trigger="hover" popper-class="tf-user-popover">
            <template #reference>
              <div class="tf-header__avatar">
                <img :src="photo" alt="avatar" class="tf-header__avatar-img" />
                <span class="tf-header__username">{{ isLoginToken }}</span>
                <el-icon :size="12"><CaretBottom /></el-icon>
              </div>
            </template>
            <template #default>
              <ul class="tf-user-menu">
                <li>
                  <router-link to="/personInfo/index">
                    <el-icon><User /></el-icon>个人信息
                  </router-link>
                </li>
                <li>
                  <router-link to="/accountSettings/index">
                    <el-icon><Setting /></el-icon>账号设置
                  </router-link>
                </li>
                <li>
                  <router-link to="/orderManagement/index">
                    <el-icon><List /></el-icon>订单管理
                  </router-link>
                </li>
                <li class="tf-user-menu__logout" @click="loginOut">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </li>
              </ul>
            </template>
          </el-popover>
        </template>

        <!-- 未登录：仅显示 登录 / 注册 按钮，无任何下拉菜单 -->
        <template v-else>
          <div class="tf-header__auth-btns">
            <router-link to="/login" class="tf-auth-btn tf-auth-btn--login">登录</router-link>
            <router-link to="/register" class="tf-auth-btn tf-auth-btn--register">注册</router-link>
          </div>
        </template>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
import logo from '@/assets/login/logo.png'
import photo from '@/assets/login/photo.png'
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { getToken, getUserIdKey, removeToken, removeUserIdKey, removeName, getName } from '@/utils/auth'
import { useAuthStore } from '@/store/modules/auth'
import { getPersonInfoId } from '@/api/personInfo'
import { useRoute, useRouter } from 'vue-router'
import { getCurrentCity, getHotCity, getOtherCity } from '@/api/area'
import { getProgramSearch } from '@/api/allType'
import { useMitt } from '@/utils/index'
import { DEFAULT_PAGE_SIZE } from '@/utils/constants'
import { Location, CaretBottom, Search, User, Setting, List, SwitchButton, Sunny, Moon } from '@element-plus/icons-vue'

interface CityItem { id: string; name: string; parentId?: string; type?: string }

const emits = defineEmits<{ (e: 'updateValue', value: string): void }>()

const emitter = useMitt()
const route = useRoute()
const router = useRouter()
const iptSearch = ref<string>('')
const isShowHeader = ref<boolean>(true)
const searchFocused = ref<boolean>(false)
const userStore = useAuthStore()

// ─── 核心登录状态：直接 computed 自 Pinia Store，天然响应式 ──────────────────
// userStore.token 由 Pinia persist 持久化，登录/注销均自动触发视图更新
const isHasToken = computed<boolean>(() => !!userStore.token)

// 显示名称优先级：接口昵称 > Pinia name > Cookie name > '我的'
const isLoginToken = computed<string>(() => {
  const raw = userStore.name || getName() || '我的'
  return raw.length > 8 ? raw.slice(0, 8) + '...' : raw
})

// 亮暗主题切换相关
const isDark = ref<boolean>(false)

function initTheme() {
  const savedTheme = localStorage.getItem('tf_theme')
  if (savedTheme === 'dark' || (!savedTheme && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
    isDark.value = true
    document.documentElement.setAttribute('data-theme', 'dark')
  } else {
    isDark.value = false
    document.documentElement.setAttribute('data-theme', 'light')
  }
}

function toggleTheme() {
  isDark.value = !isDark.value
  const theme = isDark.value ? 'dark' : 'light'
  document.documentElement.setAttribute('data-theme', theme)
  localStorage.setItem('tf_theme', theme)
}

// 城市相关
const localName = ref<string>('')
const localId = ref<string>('')
const hotCity = ref<CityItem[]>([])
const otherCity = ref<CityItem[]>([])
const cityPanelOpen = ref<boolean>(false)
const citySearchKey = ref<string>('')
const cityTabActive = ref<string>('all')
const cityRef = ref<HTMLElement | null>(null)

const queryParams = ref({
  content: '',
  pageNumber: 1,
  pageSize: DEFAULT_PAGE_SIZE,
  timeType: 0,
})

const path = route.path
isShowHeader.value = path !== '/login'

// ─── 字母分组 Tab 定义 ─────────────────────────────────────────────────────────
const letterTabs = [
  { key: 'all', label: '全部' },
  { key: 'AE',  label: 'A-E' },
  { key: 'FJ',  label: 'F-J' },
  { key: 'KN',  label: 'K-N' },
  { key: 'PW',  label: 'P-W' },
  { key: 'XZ',  label: 'X-Z' },
]

// ─── 字母分组字典：利用 localeCompare 'zh-CN' 比较汉字拼音边界 ────────────────
// 边界字是每个声母区间最小的汉字（依据 GB2312 排序）
// A: 啊(a), B: 芭(b), C: 擦(c), D: 搭(d), E: 蛾(e)
// F: 发(f), G: 噶(g), H: 哈(h), I/J: 击(j)
// K: 喀(k), L: 垃(l), M: 妈(m), N: 拿(n)
// O/P: 啪(p), Q: 期(q), R: 然(r), S: 撒(s), T: 塌(t)
// W: 挖(w), X: 昔(x), Y: 压(y), Z: 匝(z)
function getInitialLetter(cityName: string): string {
  if (!cityName) return '#'
  // 去掉行政后缀，取第一个字
  const raw = cityName.replace(/(市|特别行政区|自治区|省|地区|盟|州|县)$/, '')
  const ch = raw.charAt(0)

  // 如果本身就是英文字母
  if (/^[A-Za-z]/.test(ch)) return ch.toUpperCase()

  // 利用 localeCompare('zh-CN') 来判断汉字拼音首字母
  // 这里用每个声母区间的代表汉字来做边界比较
  // 顺序：Z -> Y -> X -> W -> T -> S -> R -> Q -> P -> N -> M -> L -> K -> J -> H -> G -> F -> E -> D -> C -> B -> A
  const zones: Array<{ letter: string; boundary: string }> = [
    { letter: 'Z', boundary: '匝' },
    { letter: 'Y', boundary: '压' },
    { letter: 'X', boundary: '昔' },
    { letter: 'W', boundary: '挖' },
    { letter: 'T', boundary: '塌' },
    { letter: 'S', boundary: '撒' },
    { letter: 'R', boundary: '然' },
    { letter: 'Q', boundary: '期' },
    { letter: 'P', boundary: '啪' },
    { letter: 'N', boundary: '拿' },
    { letter: 'M', boundary: '妈' },
    { letter: 'L', boundary: '垃' },
    { letter: 'K', boundary: '喀' },
    { letter: 'J', boundary: '击' },
    { letter: 'H', boundary: '哈' },
    { letter: 'G', boundary: '噶' },
    { letter: 'F', boundary: '发' },
    { letter: 'E', boundary: '蛾' },
    { letter: 'D', boundary: '搭' },
    { letter: 'C', boundary: '擦' },
    { letter: 'B', boundary: '芭' },
    { letter: 'A', boundary: '啊' },
  ]

  for (const zone of zones) {
    if (ch.localeCompare(zone.boundary, 'zh-CN') >= 0) {
      return zone.letter
    }
  }
  return '#'
}

// 规范化城市名称（去除尾部行政区后缀，用于精准比对去重）
function normalizeCityName(name: string): string {
  if (!name) return ''
  return name.trim().replace(/(市|特别行政区|自治区|省|地区|盟|州|县)$/, '')
}

// ─── 热门城市去重（按规范化名称去重，解决 "北京" / "北京市" / 重复 ID） ───────────────
const hotCityDedup = computed<CityItem[]>(() => {
  const seen = new Set<string>()
  return hotCity.value.filter(item => {
    if (!item || !item.name) return false
    const norm = normalizeCityName(item.name)
    if (seen.has(norm)) return false
    seen.add(norm)
    return true
  })
})

// ─── 搜索过滤（在 otherCity 中搜索，按规范化名称去重并按拼音排序） ───────────────
const searchedCities = computed<CityItem[]>(() => {
  const key = citySearchKey.value.trim()
  if (!key) return []
  const lower = key.toLowerCase()
  const seen = new Set<string>()
  const filtered = otherCity.value.filter(item => {
    if (!item || !item.name) return false
    if (!item.name.toLowerCase().includes(lower)) return false
    const norm = normalizeCityName(item.name)
    if (seen.has(norm)) return false
    seen.add(norm)
    return true
  })
  return filtered.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
})

// ─── 字母分组过滤 ──────────────────────────────────────────────────────────────
const LETTER_RANGE: Record<string, string[]> = {
  'AE': ['A', 'B', 'C', 'D', 'E'],
  'FJ': ['F', 'G', 'H', 'I', 'J'],
  'KN': ['K', 'L', 'M', 'N'],
  'PW': ['P', 'Q', 'R', 'S', 'T', 'W'],
  'XZ': ['X', 'Y', 'Z'],
}

const groupedCities = computed<CityItem[]>(() => {
  const seen = new Set<string>()
  let list = otherCity.value.slice()
  if (cityTabActive.value !== 'all') {
    const allowed = LETTER_RANGE[cityTabActive.value] || []
    list = list.filter(item => allowed.includes(getInitialLetter(item.name)))
  }
  const deduped = list.filter(item => {
    if (!item || !item.name) return false
    const norm = normalizeCityName(item.name)
    if (seen.has(norm)) return false
    seen.add(norm)
    return true
  })
  return deduped.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
})

// ─── 城市面板开关 ──────────────────────────────────────────────────────────────
function toggleCityPanel() {
  cityPanelOpen.value = !cityPanelOpen.value
  if (!cityPanelOpen.value) {
    citySearchKey.value = ''
    cityTabActive.value = 'all'
  }
}

function closeCityPanel() {
  cityPanelOpen.value = false
  citySearchKey.value = ''
  cityTabActive.value = 'all'
}

// 点击页面其他地方关闭城市面板
function handleOutsideClick(e: MouseEvent) {
  if (cityRef.value && !cityRef.value.contains(e.target as Node)) {
    closeCityPanel()
  }
}

// ─── 选择城市 ─────────────────────────────────────────────────────────────────
function selectCity(params: CityItem): void {
  localName.value = params.name
  localId.value = params.id
  localStorage.setItem('userCity', JSON.stringify({ id: params.id, name: params.name }))
  emits('updateValue', localId.value)
  emitter.emit('cityChange', params)
  closeCityPanel()
}

// ─── 登录/注销 ────────────────────────────────────────────────────────────────
function loginOut(): void {
  userStore.logOut().then(() => {
    removeToken()
    removeUserIdKey()
    removeName()
    location.href = '/'
  })
}

// ─── 城市数据加载 ──────────────────────────────────────────────────────────────
onMounted(() => {
  initTheme()
  loadCurrentCity()
  loadHotCity()
  loadOtherCity()
  document.addEventListener('click', handleOutsideClick)
})

onUnmounted(() => {
  document.removeEventListener('click', handleOutsideClick)
})

function loadCurrentCity(): void {
  // 优先使用用户上次手动选择的城市
  const cachedCity = localStorage.getItem('userCity')
  if (cachedCity) {
    try {
      const parsed = JSON.parse(cachedCity)
      if (parsed.id && parsed.name) {
        localName.value = parsed.name
        localId.value = parsed.id
        emits('updateValue', localId.value)
        return
      }
    } catch (_) {
      // ignore parse error
    }
  }
  // 否则从 API 获取当前 IP 城市
  getCurrentCity()
    .then((response: any) => {
      if (response.data) {
        const { name, id } = response.data
        localName.value = name
        localId.value = id
        emits('updateValue', localId.value)
      }
    })
    .catch(() => {})
}

function loadHotCity(): void {
  getHotCity()
    .then((response: any) => {
      hotCity.value = response.data || []
    })
    .catch(() => {})
}

function loadOtherCity(): void {
  getOtherCity()
    .then((response: any) => {
      otherCity.value = response.data || []
    })
    .catch(() => {})
}

// ─── 搜索演出 ─────────────────────────────────────────────────────────────────
function getProgramSearchList(): void {
  queryParams.value.content = iptSearch.value
  getProgramSearch(queryParams.value).then((response: any) => {
    emitter.emit('searchList', response.data)
    router.push({ name: 'AllType' })
  })
}
</script>

<style scoped lang="scss">
.tf-header {
  position: sticky;
  top: 0;
  z-index: 1000;
  width: 100%;
  height: 64px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-bottom: 1px solid var(--tf-border);
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.05);

  &__inner {
    max-width: var(--tf-max-width);
    margin: 0 auto;
    height: 100%;
    display: flex;
    align-items: center;
    gap: 20px;
    padding: 0 24px;
  }

  &__logo {
    flex-shrink: 0;
    img {
      width: 100px;
      height: 44px;
      object-fit: contain;
      display: block;
    }
  }

  &__nav {
    display: flex;
    gap: 4px;
    flex-shrink: 0;

    &-item {
      font-size: 15px;
      font-weight: 500;
      color: var(--tf-text-primary);
      text-decoration: none;
      padding: 6px 14px;
      border-radius: var(--tf-radius-full);
      transition: all 0.2s;

      &:hover,
      &.router-link-active {
        color: var(--tf-primary);
        background: var(--tf-primary-light);
        font-weight: 600;
      }
    }
  }

  &__city {
    position: relative;
    flex-shrink: 0;
  }

  &__city-btn {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 14px;
    font-weight: 500;
    color: var(--tf-text-primary);
    cursor: pointer;
    padding: 6px 12px;
    border-radius: var(--tf-radius-full);
    border: 1px solid var(--tf-border);
    background: #FAFAFA;
    user-select: none;
    transition: all 0.2s;

    .rotate-180 {
      transform: rotate(180deg);
      transition: transform 0.2s;
    }

    &:hover {
      border-color: var(--tf-primary);
      color: var(--tf-primary);
      background: var(--tf-primary-light);
    }
  }

  &__search {
    flex: 1;
    min-width: 0;
    max-width: 440px;
  }

  &__theme-toggle {
    flex-shrink: 0;

    .theme-btn {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-size: 13px;
      font-weight: 600;
      color: var(--tf-text-primary);
      cursor: pointer;
      padding: 6px 12px;
      border-radius: var(--tf-radius-full);
      border: 1px solid var(--tf-border);
      background: var(--tf-surface);
      user-select: none;
      transition: all 0.25s;

      &:hover {
        border-color: var(--tf-primary);
        color: var(--tf-primary);
        background: var(--tf-primary-light);
      }

      &--dark {
        background: #1A2436;
        border-color: #2D3D58;
        color: #F8FAFC;

        &:hover {
          background: #243044;
          color: #FF553E;
        }
      }
    }
  }

  &__user {
    margin-left: auto;
    flex-shrink: 0;
  }

  &__avatar {
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    padding: 5px 10px;
    border-radius: var(--tf-radius-full);
    border: 1px solid var(--tf-border);
    transition: all 0.2s;

    &:hover {
      border-color: var(--tf-primary);
      background: var(--tf-primary-light);
    }

    &-img {
      width: 28px;
      height: 28px;
      border-radius: 50%;
      object-fit: cover;
      border: 1px solid var(--tf-border);
    }
  }

  &__username {
    font-size: 14px;
    font-weight: 500;
    color: var(--tf-text-primary);
    max-width: 80px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__auth-btns {
    display: flex;
    align-items: center;
    gap: 10px;
  }
}

/* 搜索框 */
.tf-search-box {
  display: flex;
  align-items: center;
  height: 38px;
  background: var(--tf-bg);
  border: 1.5px solid transparent;
  border-radius: var(--tf-radius-full);
  padding: 0 4px 0 14px;
  transition: all 0.25s;

  &--focused {
    background: var(--tf-surface);
    border-color: var(--tf-primary);
    box-shadow: 0 0 0 3px rgba(255, 55, 29, 0.10);
  }

  &__icon {
    font-size: 15px;
    color: #9CA3AF;
    margin-right: 6px;
    flex-shrink: 0;
  }

  &__input {
    flex: 1;
    min-width: 0;
    border: none;
    background: transparent;
    font-size: 13px;
    outline: none;
    color: var(--tf-text-primary);
    &::placeholder { color: #9CA3AF; }
  }

  &__btn {
    flex-shrink: 0;
    height: 30px;
    padding: 0 16px;
    background: var(--tf-primary);
    color: #fff;
    border: none;
    border-radius: var(--tf-radius-full);
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;
    transition: background 0.2s;
    &:hover { background: var(--tf-primary-hover); }
  }
}

/* 登录/注册按钮 */
.tf-auth-btn {
  text-decoration: none;
  font-size: 14px;
  font-weight: 600;
  padding: 7px 20px;
  border-radius: var(--tf-radius-full);
  transition: all 0.2s;

  &--login {
    color: var(--tf-primary);
    border: 1.5px solid var(--tf-primary);
    background: transparent;
    &:hover {
      background: var(--tf-primary-light);
    }
  }

  &--register {
    background: var(--tf-primary);
    color: #fff !important;
    border: 1.5px solid var(--tf-primary);
    &:hover {
      background: var(--tf-primary-hover);
      box-shadow: 0 4px 14px rgba(255, 55, 29, 0.30);
    }
  }
}

/* ─── 城市下拉浮层 ────────────────────────────────────────────── */
.city-dropdown {
  position: absolute;
  top: calc(100% + 10px);
  left: 0;
  z-index: 2000;
  width: 560px;
  background: var(--tf-surface);
  border: 1px solid var(--tf-border);
  border-radius: var(--tf-radius-lg);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.12), 0 2px 8px rgba(0, 0, 0, 0.06);
  overflow: hidden;

  &__header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 14px 18px;
    border-bottom: 1px solid var(--tf-border);
    background: var(--tf-bg);
  }

  &__current {
    font-size: 13px;
    color: var(--tf-text-secondary);
    display: flex;
    align-items: center;
    gap: 6px;
  }

  &__body {
    padding: 14px 18px;
  }

  &__section {
    padding: 14px 18px;
    & + & {
      border-top: 1px solid var(--tf-border);
    }
  }
}

.city-current-badge {
  font-weight: 700;
  color: var(--tf-primary);
  background: var(--tf-primary-light);
  padding: 3px 10px;
  border-radius: var(--tf-radius-full);
  font-size: 12px;
}

.city-section-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--tf-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 10px;
}

.city-section-title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

/* 热门城市 Chips */
.city-hot-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.city-hot-chip {
  padding: 6px 14px;
  background: var(--tf-bg);
  border: 1px solid var(--tf-border);
  border-radius: var(--tf-radius-sm);
  font-size: 13px;
  color: var(--tf-text-primary);
  cursor: pointer;
  transition: all 0.18s;
  user-select: none;

  &:hover {
    border-color: var(--tf-primary);
    color: var(--tf-primary);
    background: var(--tf-primary-light);
  }

  &--active {
    background: var(--tf-primary) !important;
    color: #fff !important;
    border-color: var(--tf-primary) !important;
    font-weight: 600;
  }
}

/* 字母 Tab */
.city-letter-tabs {
  display: flex;
  gap: 4px;
}

.city-letter-tab {
  padding: 3px 9px;
  border-radius: var(--tf-radius-sm);
  font-size: 12px;
  font-weight: 500;
  color: var(--tf-text-secondary);
  cursor: pointer;
  border: 1px solid transparent;
  user-select: none;
  transition: all 0.15s;

  &:hover {
    color: var(--tf-primary);
    border-color: var(--tf-primary);
    background: var(--tf-primary-light);
  }

  &--active {
    background: var(--tf-primary) !important;
    color: #fff !important;
    border-color: var(--tf-primary) !important;
    font-weight: 600;
  }
}

/* 城市网格 */
.city-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
  max-height: 200px;
  overflow-y: auto;
  padding-right: 2px;

  &::-webkit-scrollbar {
    width: 4px;
  }
  &::-webkit-scrollbar-track { background: transparent; }
  &::-webkit-scrollbar-thumb {
    background: var(--tf-border);
    border-radius: 2px;
  }
}

.city-grid-item {
  padding: 6px 4px;
  font-size: 12px;
  color: var(--tf-text-primary);
  text-align: center;
  border-radius: var(--tf-radius-sm);
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: all 0.15s;
  user-select: none;
  border: 1px solid transparent;

  &:hover {
    background: var(--tf-primary-light);
    color: var(--tf-primary);
    border-color: var(--tf-primary);
  }

  &--active {
    color: var(--tf-primary) !important;
    font-weight: 700;
    background: var(--tf-primary-light) !important;
    border-color: var(--tf-primary) !important;
  }
}

.city-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: var(--tf-text-muted);
}

/* ─── 用户菜单 ────────────────────────────────────────────────── */
:deep(.tf-user-popover) {
  padding: 6px 0 !important;
}

.tf-user-menu {
  list-style: none;
  margin: 0;
  padding: 0;

  li {
    a, &.tf-user-menu__logout {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 9px 16px;
      font-size: 13px;
      color: var(--tf-text-primary);
      text-decoration: none;
      cursor: pointer;
      transition: background 0.15s;

      &:hover {
        background: var(--tf-bg);
        color: var(--tf-primary);
      }
    }
  }

  &__logout {
    border-top: 1px solid var(--tf-border);
    margin-top: 4px;
    color: #EF4444 !important;

    &:hover {
      color: #EF4444 !important;
      background: #FEF2F2 !important;
    }
  }
}
</style>

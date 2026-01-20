import { defineStore } from 'pinia'
import { loginApi, logoutApi } from '@/api/login'
import { CHANNEL_CODE } from '@/utils/constants'
import { setToken, setUserIdKey, setName } from '@/utils/auth'

export const useAuthStore = defineStore('auth', {
    state: () => ({
        token: '',
        name: '',
        avatar: '',
        userId: '',
        roles: [] as string[],
        permissions: [] as string[]
    }),
    persist: true, // pinia-plugin-persistedstate
    actions: {
        async login(userInfo: any) {
            const res = await loginApi(userInfo)
            if (res.code == 0) {
                this.token = res.data.token
                this.name = userInfo.mobile ? userInfo.mobile : userInfo.email
                this.userId = String(res.data.userId)
                setToken(res.data.token)
                setUserIdKey(String(res.data.userId))
                setName(this.name)
                return res;
            } else {
                return Promise.reject(res)
            }
        },
        async logOut() {
            try {
                if (this.token) {
                    await logoutApi({ code: CHANNEL_CODE, token: this.token })
                }
            } finally {
                this.token = ''
                this.name = ''
                this.userId = ''
                this.roles = []
                this.permissions = []
            }
        }
    }
})

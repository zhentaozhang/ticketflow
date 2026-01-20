import axios, { AxiosInstance, InternalAxiosRequestConfig, AxiosResponse } from 'axios'
import { KJUR, hextob64 } from "jsrsasign";
import { useAuthStore } from '@/store/modules/auth'
import { ElMessageBox } from 'element-plus'

axios.defaults.headers['Content-Type'] = 'application/json;charset=utf-8'

export let isRelogin = { show: false };

const request: AxiosInstance = axios.create({
    baseURL: import.meta.env.VITE_APP_BASE_API as string,
    timeout: 10000,
    headers: import.meta.env.VITE_SIGN_FLAG == 1 ? { no_verify: false } : { no_verify: true }
});

request.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => {
        const signFlag = import.meta.env.VITE_SIGN_FLAG
        if (config.data != undefined && config.data != null && config.data !== '' && signFlag == 1) {
            config.data = sign(config.data)
        }
        
        const token = useAuthStore().token;
        if (token) {
            config.headers = Object.assign(config.headers || {}, { token: token }) as any;
        }
        return config;
    },
    error => {
        return Promise.reject(error);
    }
);

request.interceptors.response.use(
    (response: AxiosResponse) => {
        const url = response.config.url;
        if ('/ticketflow/user/user/logout' == url) {
            return response.data;
        }
        const code = response.data.code
        if (code == "10055" || code == "516") {
            if (!isRelogin.show) {
                isRelogin.show = true;
                ElMessageBox.confirm('登录状态已过期，您可以继续留在该页面，或者重新登录', '系统提示', { confirmButtonText: '重新登录', cancelButtonText: '取消', type: 'warning' })
                    .then(() => {
                        isRelogin.show = false;
                        useAuthStore().logOut().then(() => {
                            location.href = '/login'
                        })
                    }).catch(() => {
                        isRelogin.show = false;
                    });
            }
            return Promise.reject('无效的会话，或者会话已过期，请重新登录。')
        } else {
            return response.data;
        }
    },
    error => {
        return Promise.reject(error);
    }
);

export function sign(params: any) {
    const code = import.meta.env.VITE_CODE as string
    const paramsStr = JSON.stringify(params)
    const signParam = { businessBody: paramsStr, code: code }

    const sig = new KJUR.crypto.Signature({ alg: "SHA256withRSA" });
    sig.init("-----BEGIN PRIVATE KEY-----" + import.meta.env.VITE_SIGN_SECRET_KEY + "-----END PRIVATE KEY-----");
    sig.updateString(buildParam(signParam));
    let sign = hextob64(sig.sign());

    return { code: code, businessBody: paramsStr, sign: sign };
}

const buildKeyValue = (key: string, value: string, isEncode: boolean) => {
    let result = `${key}=`;
    if (isEncode) {
        try {
            result += encodeURIComponent(value);
        } catch (error) {
            result += value;
        }
    } else {
        result += value;
    }
    return result;
};

const buildParam = (params: Record<string, any>) => {
    const keys = Object.keys(params).sort();
    let queryString = '';

    keys.forEach((key, index) => {
        const value = params[key];
        queryString += buildKeyValue(key, value, false);
        if (index < keys.length - 1) {
            queryString += '&';
        }
    });

    return queryString;
};

export default request

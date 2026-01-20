import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from "path";
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import type { ConfigEnv, UserConfig } from 'vite'

// https://vitejs.dev/config/
export default defineConfig(({ mode }: ConfigEnv): UserConfig => {
    const env = loadEnv(mode, process.cwd())
    const { VITE_APP_ENV, VITE_APP_BASE_API, VITE_APP_URL } = env
    
    return {
        base: VITE_APP_ENV === 'production' ? '/' : '/',
        plugins: [
            vue(),
            AutoImport({
                resolvers: [ElementPlusResolver()],
            }),
            Components({
                resolvers: [ElementPlusResolver()],
            }),
        ],
        resolve: {
            alias: {
                '~': path.resolve(__dirname, './'),
                '@': path.resolve(__dirname, './src')
            },
            extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue']
        },
        server: {
            host: true,
            hmr: true,
            open: false,
            proxy: {
                [VITE_APP_BASE_API]: {
                    target: VITE_APP_URL,
                    changeOrigin: true,
                    rewrite: (p) => p.replace(/^\/api/, ''),
                    bypass(req, res, options) {
                        const target = (options as any).target || '';
                        const realUrl = target + ((options as any).rewrite ? (options as any).rewrite(req?.url || '') : '');
                        res.setHeader('A-Real-Url', realUrl);
                    },
                },
            },
        },
        build: {
            chunkSizeWarningLimit: 1500,
            rollupOptions: {
                output: {
                    manualChunks(id) {
                        if (id.includes('node_modules')) {
                            if (id.includes('element-plus') || id.includes('@element-plus')) {
                                return 'element-plus'
                            }
                            if (id.includes('vue') || id.includes('pinia') || id.includes('vue-router')) {
                                return 'vue-vendor'
                            }
                            return 'vendor'
                        }
                    }
                }
            }
        }
    }
})

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({plugins:[vue()],server:{proxy:{'/api':{target:process.env.API_PROXY_TARGET??'http://127.0.0.1:18865',changeOrigin:false}}},preview:{proxy:{'/api':{target:process.env.API_PROXY_TARGET??'http://127.0.0.1:18865',changeOrigin:false}}}})

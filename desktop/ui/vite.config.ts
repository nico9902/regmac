import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
// La build finisce dentro il jar dell'app desktop (risorse statiche di Spring).
export default defineConfig({plugins:[react()],build:{outDir:'../app/src/main/resources/static',emptyOutDir:true},server:{proxy:{'/api':'http://127.0.0.1:8080'}}});

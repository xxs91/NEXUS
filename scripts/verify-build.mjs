import { access, readFile } from 'node:fs/promises';
await access('dist/index.html');
const html=await readFile('dist/index.html','utf8');
if(!html.includes('<html')||!html.includes('createClient')) throw new Error('dist/index.html missing app content');
console.log('NEXUS web assets verified: dist/index.html');

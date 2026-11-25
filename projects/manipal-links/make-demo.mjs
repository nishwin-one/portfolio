import {readFile,writeFile} from 'node:fs/promises';
const load=(name)=>readFile(new URL('./public/'+name,import.meta.url),'utf8');
const [html,css,js]=await Promise.all(['index.html','style.css','app.js'].map(load));
const demo=html.replace('<html lang="en">','<html lang="en" data-mode="demo">').replace('<link rel="stylesheet" href="style.css">',()=>'<style>'+css+'</style>').replace('<script src="app.js"></script>',()=>'<script>'+js+'</script>');
await writeFile(process.argv[2]||'Manipal Links - Demo.html',demo);

import http from 'node:http';
import {DatabaseSync} from 'node:sqlite';
import {readFile} from 'node:fs/promises';
import {randomBytes, scryptSync, timingSafeEqual, createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
const here=new URL('./public/',import.meta.url);
const files={
 '/':['index.html','text/html; charset=utf-8'],
 '/app.js':['app.js','text/javascript; charset=utf-8'],
 '/style.css':['style.css','text/css; charset=utf-8']
};
const HOUR=3600000;
const hex=n=>randomBytes(n).toString('hex');
const hash=t=>createHash('sha256').update(t).digest('hex');
const str=(x,n)=>typeof x==='string'?x.trim().slice(0,n):'';
const fail=(status,message)=>{throw Object.assign(new Error(message),{status})};
const safeEq=(a,b)=>timingSafeEqual(Buffer.from(hash(a),'hex'),Buffer.from(hash(b),'hex'));
const asUser=(id,name,detail)=>({id,name,detail});
const cats=new Set(['Hackathon','Gym','Study','Other']);
export function makeServer({file='manipal-links.sqlite',invite,secure=false}={}){
 if(typeof invite!=='string'||invite.trim().length<10)throw Error('Set ML_INVITE_CODE to a private pilot code of at least 10 characters.');
 const db=new DatabaseSync(file);db.exec(
 'PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;'+
 'CREATE TABLE IF NOT EXISTS people (id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, name TEXT NOT NULL, detail TEXT NOT NULL, salt TEXT NOT NULL, secret TEXT NOT NULL);'+
 'CREATE TABLE IF NOT EXISTS sessions (token TEXT PRIMARY KEY, person TEXT NOT NULL REFERENCES people(id), expires INTEGER NOT NULL);'+
 'CREATE TABLE IF NOT EXISTS posts (id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES people(id), category TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL);'+
 'CREATE TABLE IF NOT EXISTS requests (id TEXT PRIMARY KEY, post TEXT NOT NULL REFERENCES posts(id), from_id TEXT NOT NULL REFERENCES people(id), to_id TEXT NOT NULL REFERENCES people(id), status TEXT NOT NULL, intro TEXT NOT NULL);'+
 'CREATE UNIQUE INDEX IF NOT EXISTS one_active_request ON requests(post,from_id) WHERE status <> \'declined\';'+
 'CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY, request TEXT NOT NULL REFERENCES requests(id), author TEXT NOT NULL REFERENCES people(id), text TEXT NOT NULL);'+
 'CREATE INDEX IF NOT EXISTS message_thread ON messages(request,id);');
 const stmt=(sql)=>db.prepare(sql);
 const privatePosts=stmt('SELECT p.*, u.name, u.detail FROM posts p JOIN people u ON u.id=p.owner ORDER BY p.rowid DESC LIMIT 250');
 const related=stmt('SELECT r.*, p.title, u.id AS peer, u.name, u.detail FROM requests r JOIN posts p ON p.id=r.post JOIN people u ON u.id=CASE WHEN r.from_id=? THEN r.to_id ELSE r.from_id END WHERE r.from_id=? OR r.to_id=? ORDER BY r.rowid DESC');
 const sessionLookup=stmt('SELECT u.id,u.name,u.detail FROM sessions s JOIN people u ON u.id=s.person WHERE s.token=? AND s.expires>?');
 const attempts=new Map();
 const rate=(key,cap,span)=>{const now=Date.now();if(attempts.size>2500)for(const [k,v] of attempts)if(v.until<=now)attempts.delete(k);if(attempts.size>4500&&!attempts.has(key))fail(429,'Try again later.');const a=attempts.get(key);if(!a||a.until<=now){attempts.set(key,{count:1,until:now+span});return;}if(++a.count>cap)fail(429,'Too many attempts. Try again later.');};
 const authSession=(req)=>{const m=/(?:^|;\s*)ml_sid=([a-f0-9]{64})(?:;|$)/.exec(req.headers.cookie??'');return m?.[1];};
 const cookie=(t,age)=>'ml_sid='+t+'; Path=/; Max-Age='+age+'; HttpOnly; SameSite=Strict'+(secure?'; Secure':'');
 const json=(res,status,obj)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(obj));};
 const read=async req=>{if(!/^application\/json(?:\s*;|$)/i.test(req.headers['content-type']??''))fail(415,'Expected JSON.');const parts=[];let n=0;for await(const c of req){n+=c.length;if(n>8192)fail(413,'Payload too large.');parts.push(c);}let value;try{value=JSON.parse(Buffer.concat(parts).toString());}catch{fail(400,'Invalid JSON.');}if(!value||typeof value!=='object'||Array.isArray(value))fail(400,'Invalid JSON object.');return value;};
 const server=http.createServer(async(req,res)=>{
  res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','no-referrer');res.setHeader('X-Frame-Options','DENY');res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
  try{
   const path=new URL(req.url,'http://localhost').pathname;
   if(!path.startsWith('/api/')){
    if(req.method!=='GET'||!Object.hasOwn(files,path))return json(res,404,{error:'Page not found.'});
    const [f,type]=files[path];res.writeHead(200,{'Content-Type':type});return res.end(await readFile(new URL(f,here)));
   }
   const route=path.slice(5),method=req.method;const session=authSession(req),me=session?sessionLookup.get(hash(session),Date.now())??null:null;
   if(method==='GET'&&route==='state'){
    const posts=me?privatePosts.all().map(({name,detail,...r})=>({...r,author:asUser(r.owner,name,detail)})):[];
    const requests=me?related.all(me.id,me.id,me.id).map(({id,post,from_id,to_id,status,intro,title,peer,name,detail})=>({id,post,from:from_id,to:to_id,status,intro,title,other:asUser(peer,name,detail)})):[];
    return json(res,200,{me,posts,requests});
   }
   if(method==='POST'){
    if(req.headers['sec-fetch-site']==='cross-site')fail(403,'Use the Manipal Links page.');
    if(req.headers.origin){let origin;try{origin=new URL(req.headers.origin);}catch{fail(403,'Use the Manipal Links page.');}if(origin.host!==req.headers.host)fail(403,'Use the Manipal Links page.');}
   }
   if(method==='POST'&&(route==='login'||route==='register')){
    rate('login:'+req.socket.remoteAddress,18,10*60000);
    const b=await read(req),username=str(b.username,31).toLowerCase(),password=b.password;
    if(!/^[a-z0-9_.-]{3,30}$/.test(username)||typeof password!=='string'||password.length<10||password.length>128)fail(400,'Use a 3–30 character username and a 10–128 character password.');
    let id;
    if(route==='register'){
     if(typeof b.invite!=='string'||!safeEq(b.invite,invite))fail(403,'That pilot invite code is not valid.');
     const name=str(b.name,60),detail=str(b.detail,80);if(!name||!detail)fail(400,'Enter a display name and course or interest.');
     if(stmt('SELECT id FROM people WHERE username=?').get(username))fail(409,'That username is taken.');
     id=hex(12);const salt=hex(16);const secret=scryptSync(password,salt,64).toString('hex');
     stmt('INSERT INTO people VALUES (?,?,?,?,?,?)').run(id,username,name,detail,salt,secret);
    }else{
     const user=stmt('SELECT id,salt,secret FROM people WHERE username=?').get(username);
     const candidate=scryptSync(password,user?.salt??'invalid-account-salt',64).toString('hex');
     if(!user||!safeEq(candidate,user.secret))fail(401,'Incorrect username or password.');
     id=user.id;
    }
    const raw=hex(32);db.prepare('DELETE FROM sessions WHERE expires<=?').run(Date.now());
    if(session)stmt('DELETE FROM sessions WHERE token=?').run(hash(session));
    stmt('INSERT INTO sessions VALUES (?,?,?)').run(hash(raw),id,Date.now()+7*24*HOUR);
    res.setHeader('Set-Cookie',cookie(raw,7*24*3600));return json(res,200,{ok:true});
   }
   if(!me)fail(401,'Sign in first.');
   if(method==='POST'&&route==='logout'){await read(req);stmt('DELETE FROM sessions WHERE token=?').run(hash(session));res.setHeader('Set-Cookie',cookie('',0));return json(res,200,{ok:true});}
   if(method==='POST')rate('post:'+me.id,80,60000);
   if(method==='POST'&&route==='posts'){
    const b=await read(req),title=str(b.title,85),body=str(b.body,360);if(!title||!body||!cats.has(b.category))fail(400,'Enter a title, purpose, and details.');
    const id=hex(12);stmt('INSERT INTO posts VALUES (?,?,?,?,?)').run(id,me.id,b.category,title,body);return json(res,201,{id});
   }
   if(method==='POST'&&route==='requests'){
    const b=await read(req),p=stmt('SELECT id,owner FROM posts WHERE id=?').get(str(b.post,24));
    if(!p)fail(404,'Post not found.');if(me.id===p.owner)fail(400,'You can’t request your own post.');
    if(stmt("SELECT id FROM requests WHERE from_id=? AND post=? AND status<>'declined'").get(me.id,p.id))fail(409,'A request is already active.');
    const id=hex(12);stmt('INSERT INTO requests VALUES (?,?,?,?,?,?)').run(id,p.id,me.id,p.owner,'pending',str(b.intro,280));return json(res,201,{id});
   }
   const answer=/^requests\/([a-f0-9]{24})\/(accept|decline)$/.exec(route);
   if(method==='POST'&&answer){await read(req);const row=stmt('SELECT to_id,status FROM requests WHERE id=?').get(answer[1]);
    if(!row||row.to_id!==me.id)fail(404,'Request not found.');
    if(row.status!=='pending')fail(409,'This request has already been answered.');
    stmt('UPDATE requests SET status=? WHERE id=?').run(answer[2]==='accept'?'accepted':'declined',answer[1]);return json(res,200,{ok:true});
   }
   const chat=/^messages\/([a-f0-9]{24})$/.exec(route);
   if(chat&&(method==='GET'||method==='POST')){
    const row=stmt('SELECT from_id,to_id,status FROM requests WHERE id=?').get(chat[1]);
    if(!row||![row.from_id,row.to_id].includes(me.id))fail(404,'Chat not found.');
    if(row.status!=='accepted')fail(403,'Chat unlocks only after acceptance.');
    if(method==='GET'){const messages=stmt('SELECT id,author,text FROM messages WHERE request=? ORDER BY id DESC LIMIT 200').all(chat[1]).reverse();return json(res,200,{messages});}
    const b=await read(req),msg=str(b.text,1000);if(!msg)fail(400,'Write a message.');
    stmt('INSERT INTO messages(request,author,text) VALUES (?,?,?)').run(chat[1],me.id,msg);return json(res,201,{ok:true});
   }
   fail(404,'Action not found.');
  }catch(e){if(!e.status)console.error(e);if(!res.headersSent)json(res,e.status||500,{error:e.status?e.message:'The server couldn’t complete that action.'});else res.end();}
 });
 server.on('close',()=>db.close());return server;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const server=makeServer({file:process.env.ML_DB||'manipal-links.sqlite',invite:process.env.ML_INVITE_CODE,secure:process.env.ML_HTTPS==='1'});
 const host=process.env.ML_HOST||'127.0.0.1', port=Number(process.env.PORT||3000);
 server.listen(port,host,()=>console.log('Manipal Links: http://'+host+':'+port));
}


const assert=require('node:assert/strict');
const {mkdirSync}=require('node:fs');
const {chromium}=require('playwright');
const http=require('node:http');
const base=process.argv[2]||'http://127.0.0.1:8877';
if(new URL(base).hostname!=='127.0.0.1'||new URL(base).port!=='8877')throw Error('Use only the isolated schedule_test service at 8877.');
const password='OnlyForIntegrationTests!2026';
(async()=>{
  const modelRequests=[];
  const fixture=http.createServer((req,res)=>{let body='';req.on('data',chunk=>body+=chunk);req.on('end',()=>{try{assert.equal(req.url,'/v1/chat/completions');assert.equal(req.headers.authorization,'Bearer browser-fixture-key');assert(!body.includes('browser-fixture-key'));const payload=JSON.parse(body);modelRequests.push(payload);const diagnostic=payload.messages.length===1;const content=diagnostic?{ok:true}:{title:'模型工作安排',target_id:2,start:null,end:null,location:'5号会议室',notes:'测试会议资料',warnings:[]};res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({choices:[{message:{content:JSON.stringify(content)}}]}));}catch(e){res.writeHead(500);res.end('{}');}});});
  await new Promise(resolve=>fixture.listen(0,'127.0.0.1',resolve));
  const modelBase='http://127.0.0.1:'+fixture.address().port+'/v1';
  const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_EXECUTABLE});
  const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  const titles={dashboard:'你好，刘总',calendar:'我的日程',execution:'执行看板',ai:'AI 日程助手',teams:'团队管理',notifications:'通知中心',settings:'账户与管理',metrics:'协作分析',conflicts:'冲突管理'}; const nav=async id=>{await page.locator(`nav [data-id="${id}"]`).click();await page.waitForURL(base+'/'+id);await page.waitForFunction(title=>document.querySelector('#main h1')?.textContent.includes(title),titles[id]);await page.waitForTimeout(80);};
  const state=async client=>(await client.get(base+'/api/state')).json();
  mkdirSync('.runtime',{recursive:true});
  try{
    assert.equal((await (await page.request.get(base+'/api/health')).json()).architecture,'spring-boot-postgresql');
    await page.goto(base);await page.getByRole('button',{name:'管理员注册',exact:true}).click();
    await page.getByLabel('姓名',{exact:true}).fill('刘总');await page.getByLabel('账号',{exact:true}).fill('liuzong');
    await page.getByLabel('密码',{exact:true}).fill(password);await page.getByLabel('确认密码',{exact:true}).fill(password);
    await page.getByRole('button',{name:'注册并登录 →',exact:true}).click();await page.locator('#main h1').waitFor();
    await nav('teams');await page.getByRole('button',{name:'＋ 新建团队',exact:true}).click();
    await page.getByLabel('团队名称',{exact:true}).fill('三组');await page.getByRole('button',{name:'保存',exact:true}).click();await page.getByRole('dialog').waitFor({state:'hidden'});
    for(const [username,name] of [['zhao','赵明'],['chen','陈晓']]){
      await page.getByRole('button',{name:'＋ 添加成员',exact:true}).click();await page.getByLabel('登录账号',{exact:true}).fill(username);await page.getByLabel('姓名',{exact:true}).fill(name);await page.getByLabel('初始密码',{exact:true}).fill(password);await page.getByLabel('团队',{exact:true}).selectOption({label:'三组'});await page.getByRole('button',{name:'保存',exact:true}).click();await page.getByRole('dialog').waitFor({state:'hidden'});
    }
    await nav('dashboard');await page.screenshot({path:'.runtime/dashboard-desktop.png',fullPage:true});

    await nav('ai');
    const respond=async action=>{const reply=page.waitForResponse(r=>r.url().endsWith('/api/ai/parse')&&r.request().method()==='POST');await action();const r=await reply;assert.equal(r.status(),200,await r.text());await page.waitForTimeout(120);return r.json();};
    await page.getByLabel('日程安排内容',{exact:true}).fill('让三组的小赵下午三点到5号会议室开会，带上他们组的所有人');
    await respond(()=>page.getByRole('button',{name:'解析安排 →',exact:true}).click());
    assert.equal(await page.locator('input[name="start"]').inputValue(),'');assert.equal(await page.locator('input[name="end"]').inputValue(),'');
    await respond(()=>page.getByRole('button',{name:'明天',exact:true}).click());assert.equal(await page.locator('input[name="end"]').inputValue(),'');
    await respond(()=>page.getByRole('button',{name:'持续1小时',exact:true}).click());
    await page.getByLabel('日程标题',{exact:true}).fill('团队工作会议（补充议题）');
    await page.getByLabel('日程安排内容',{exact:true}).fill('换到4号会议室');await respond(()=>page.getByRole('button',{name:'继续对话 →',exact:true}).click());
    assert.equal(await page.getByLabel('日程标题',{exact:true}).inputValue(),'团队工作会议（补充议题）');assert.equal(await page.locator('input[name="location"]').inputValue(),'4号会议室');
    await page.getByRole('button',{name:'选用方案 1 并重新检查',exact:true}).click();await page.waitForTimeout(300);assert.equal(await page.locator('.planning-card').count(),3);
    await nav('calendar');await nav('ai');assert.equal(await page.getByLabel('日程标题',{exact:true}).inputValue(),'团队工作会议（补充议题）');
    await page.screenshot({path:'.runtime/ai-desktop.png',fullPage:true});await page.getByRole('button',{name:'确认并发送',exact:true}).click();await page.getByRole('button',{name:'确认并发送',exact:true}).waitFor({state:'hidden'});
    let adminState=await state(page.request);assert.equal(adminState.events.length,1);let event=adminState.events[0];assert.equal(event.target_name,'赵明');assert.equal(event.status,'pending');assert.equal(event.checklist.length,2);
    await page.getByLabel('日程安排内容',{exact:true}).fill('把刚才那场会推迟半小时');const changed=await respond(()=>page.getByRole('button',{name:'继续对话 →',exact:true}).click());assert.equal(changed.draft.action,'update');assert.equal((await state(page.request)).events[0].start,event.start);
    await page.screenshot({path:'.runtime/ai-reschedule-desktop.png',fullPage:true});await page.getByRole('button',{name:'确认修改原日程',exact:true}).click();await page.getByRole('button',{name:'确认修改原日程',exact:true}).waitFor({state:'hidden'});
    adminState=await state(page.request);assert.equal(adminState.events.length,1);event=adminState.events[0];assert.equal(event.start,changed.draft.start);await page.reload();await page.locator('#main h1').waitFor();await page.waitForTimeout(400);assert.equal(await page.getByRole('button',{name:'确认并发送',exact:true}).count(),0);
    const zhao=await browser.newContext(),chen=await browser.newContext();await zhao.request.post(base+'/api/auth/login',{data:{username:'zhao',password}});await chen.request.post(base+'/api/auth/login',{data:{username:'chen',password}});assert.equal((await state(zhao.request)).events.length,1);assert.equal((await state(chen.request)).events.length,0);assert.equal((await state(chen.request)).notifications.length,0);
    const unauthorized=await chen.request.post(base+'/api/events/'+event.id+'/respond',{data:{status:'accepted',expected_updated_at:event.updated_at}});assert.equal(unauthorized.status(),403);
    const badOrigin=await page.request.post(base+'/api/notifications/read',{data:{},headers:{Origin:'https://unapproved.example'}});assert.equal(badOrigin.status(),403);
    const zp=await zhao.newPage();zp.on('pageerror',e=>errors.push(e.message));await zp.goto(base+'/calendar');await zp.locator('#main h1').waitFor();await zp.getByLabel('选择日期').fill(event.start.slice(0,10));await zp.getByRole('button',{name:new RegExp(event.title)}).first().click();await zp.getByRole('button',{name:'接受安排',exact:true}).click();await zp.getByRole('button',{name:'开始执行',exact:true}).waitFor();
    for(const input of await zp.locator('.checklist input[type="checkbox"]').all()){await input.check();await zp.waitForTimeout(180)}
    await zp.getByRole('button',{name:'开始执行',exact:true}).click();await zp.getByRole('button',{name:'完成安排',exact:true}).click();await zp.getByLabel('完成反馈',{exact:true}).fill('已完成讨论，后续责任人已明确。');await zp.getByRole('button',{name:'确认完成',exact:true}).click();await zp.waitForTimeout(250);assert.equal((await state(zhao.request)).events[0].phase,'completed');
    await zp.getByRole('button',{name:'关闭对话框'}).click();await zhao.close();await chen.close();

    await nav('settings');await page.getByLabel('接口基础地址',{exact:true}).waitFor();
    await page.getByLabel('接口基础地址',{exact:true}).fill(modelBase);await page.getByLabel('模型名称',{exact:true}).fill('browser-model');await page.getByLabel('API 密钥',{exact:true}).fill('browser-fixture-key');
    const saving=page.waitForResponse(r=>r.url().endsWith('/api/admin/model-config')&&r.request().method()==='PUT');await page.getByRole('button',{name:'保存配置',exact:true}).click();const saved=await saving;assert.equal(saved.status(),200,await saved.text());const config=(await saved.json()).config;assert.equal(config.encrypted,true);assert.equal(config.has_key,true);assert(!JSON.stringify(config).includes('browser-fixture-key'));await page.waitForTimeout(160);assert.equal(await page.getByLabel('API 密钥',{exact:true}).inputValue(),'');
    const testing=page.waitForResponse(r=>r.url().endsWith('/api/admin/model-config/test'));await page.getByRole('button',{name:'测试已保存的连接',exact:true}).click();assert.equal((await testing).status(),200);assert.equal(modelRequests.length,1);assert.equal(modelRequests[0].messages.length,1);await page.screenshot({path:'.runtime/model-settings-desktop.png',fullPage:true});
    await page.reload();await page.getByLabel('接口基础地址',{exact:true}).waitFor();assert.equal(await page.getByLabel('接口基础地址',{exact:true}).inputValue(),modelBase);assert.equal(await page.getByLabel('API 密钥',{exact:true}).inputValue(),'');
    await nav('ai');await page.getByRole('button',{name:'新对话',exact:true}).click();await page.getByLabel('日程安排内容',{exact:true}).fill('让小赵明天下午三点在5号会议室开会，持续1小时');const modeled=await respond(()=>page.getByRole('button',{name:'解析安排 →',exact:true}).click());assert.equal(modeled.draft.engine,'model');assert.equal(await page.getByLabel('日程标题',{exact:true}).inputValue(),'模型工作安排');assert.equal((await state(page.request)).events.length,1);assert.equal(modelRequests.length,2);
    const regular=await browser.newContext();await regular.request.post(base+'/api/auth/login',{data:{username:'chen',password}});const rp=await regular.newPage();await rp.goto(base+'/settings');await rp.locator('#main h1').waitFor();assert.equal(await rp.getByLabel('API 密钥',{exact:true}).count(),0);assert.equal((await regular.request.get(base+'/api/admin/model-config')).status(),403);await regular.close();
    await nav('settings');await page.getByRole('button',{name:'清除配置',exact:true}).click();await page.getByRole('button',{name:'确认清除并停用',exact:true}).click();await page.getByRole('button',{name:'清除配置',exact:true}).waitFor({state:'hidden'});assert.equal((await state(page.request)).ai_mode,'rules');
    for(const id of ['dashboard','calendar','execution','teams','notifications','settings','metrics','conflicts','ai']){await nav(id);await page.waitForTimeout(80);assert(await page.locator('#main h1').isVisible());}
    await nav('calendar');await page.getByRole('button',{name:'＋ 新建日程',exact:true}).click();await page.getByLabel('日程标题',{exact:true}).fill('尚未保存的安排');await page.keyboard.press('Escape');await page.getByRole('button',{name:'继续编辑',exact:true}).waitFor();await page.getByRole('button',{name:'放弃修改',exact:true}).click();await page.getByRole('dialog').waitFor({state:'hidden'});
    await page.setViewportSize({width:390,height:844});
    for(const id of ['dashboard','calendar','execution','teams','notifications','settings','metrics','conflicts','ai']){await nav(id);await page.waitForTimeout(80);const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1);assert.equal(overflow,false,id+' mobile overflow');}
    await nav('dashboard');await page.screenshot({path:'.runtime/dashboard-mobile.png',fullPage:true});assert.deepEqual(errors,[]);console.log('PASS: Vue routes, registration, organization, AI dispatch, single recipient, preparation/execution, draft navigation, unsaved guard, mobile layout, encrypted model settings, redaction, connection testing, actual model transport and origin guard');
  }catch(e){await page.screenshot({path:'.runtime/browser-failure.png',fullPage:true});throw e}finally{await browser.close();await new Promise(resolve=>fixture.close(resolve))}
})().catch(e=>{console.error(e);process.exitCode=1});

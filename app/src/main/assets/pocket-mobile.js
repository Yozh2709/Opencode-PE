// Kobalte touch selection can open a dialog on pointerup, then dismiss it with
// the same gesture's click. Select via the keyboard path after consuming click.
(() => {
  let touch;
  const clear = () => { touch = undefined; };
  document.addEventListener('pointercancel', clear, true);
  document.addEventListener('pointerup', event => {
    if (event.pointerType !== 'touch') return;
    const item = event.target.closest?.('[data-component="menu-v2-item"]');
    if (!item || item.getAttribute('aria-disabled') === 'true' || item.hasAttribute('data-disabled')) return;
    touch = { item, time: performance.now(), x: event.clientX, y: event.clientY };
    event.preventDefault();
    event.stopImmediatePropagation();
  }, true);
  document.addEventListener('click', event => {
    const selected = touch;
    clear();
    if (!selected || performance.now() - selected.time > 750 ||
        Math.abs(event.clientX - selected.x) > 12 || Math.abs(event.clientY - selected.y) > 12) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    if (!selected.item.isConnected) return;
    selected.item.focus();
    selected.item.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
  }, true);
})();

// Android actions stay on the authenticated local origin; no JavaScript interface
// is exposed to remote pages or embedded frames.
(() => {
  const ru = () => (document.documentElement.lang || document.cookie.match(/(?:^|;\s*)oc_locale=([^;]+)/)?.[1] || '').startsWith('ru');
  const t = (en, russian) => ru() ? russian : en;
  const action = (name, params = {}) => { location.href = '/__pocket/' + name + '?' + new URLSearchParams(params); };
  let dock, request, busy = false, composer;
  const restore = () => { if(composer)composer.style.removeProperty('display'); composer = undefined; };
  const clear = () => { restore(); dock?.remove(); dock = undefined; request = undefined; busy = false; };
  function renderPermission(next) {
    if(!next) { clear(); return; }
    // Prefer the upstream dock whenever it is present.
    if(document.querySelector('[data-component="dock-prompt"][data-kind="permission"]:not(#pocket-permission)')) { clear(); return; }
    if(request?.id === next.id && dock?.isConnected) return;
    clear(); request = next;
    dock = document.createElement('section');
    dock.id = 'pocket-permission'; dock.dataset.requestId = next.id;
    dock.dataset.component = 'dock-prompt'; dock.dataset.kind = 'permission';
    dock.setAttribute('role','region'); dock.setAttribute('aria-label',t('Permission required','Требуется разрешение'));
    const body = document.createElement('div'); body.dataset.slot = 'permission-body';
    const title = document.createElement('div'); title.dataset.slot = 'permission-header-title';
    title.textContent = t('Permission required','Требуется разрешение') + ' · ' + next.permission;
    const patterns = document.createElement('div'); patterns.dataset.slot = 'permission-patterns';
    for(const pattern of (next.patterns || [])) { const code = document.createElement('code'); code.textContent = pattern; patterns.append(code); }
    const command = next.metadata?.command;
    if(command && !(next.patterns || []).includes(command)) { const code=document.createElement('code'); code.textContent=command; patterns.append(code); }
    body.append(title,patterns);
    const footer = document.createElement('div'); footer.dataset.slot = 'permission-footer';
    const actions = document.createElement('div'); actions.dataset.slot = 'permission-footer-actions';
    for(const [reply,label,variant] of [['reject',t('Deny','Отклонить'),'ghost'],['always',t('Allow always','Всегда разрешать'),'secondary'],['once',t('Allow once','Разрешить один раз'),'primary']]) {
      const button=document.createElement('button'); button.type='button'; button.textContent=label;
      button.dataset.component='button'; button.dataset.variant=variant; button.dataset.size='normal'; button.dataset.reply=reply;
      button.onclick=()=>{ if(busy || request?.id!==next.id)return; busy=true; actions.querySelectorAll('button').forEach(b=>b.disabled=true); action('permission',{id:next.id,reply}); };
      actions.append(button);
    }
    footer.append(actions); dock.append(body,footer);
    composer=document.querySelector('[data-component="prompt-input-v2"], [data-component="prompt-input"]');
    if(composer) { composer.before(dock); composer.style.setProperty('display','none','important'); }
    else { dock.classList.add('pocket-floating-dock'); document.body.append(dock); }
  }
  function settingsLinks() {
    const nav=document.querySelector('.settings-v2 > [role="tablist"]');
    if(!nav || nav.querySelector('[data-pocket-settings]'))return;
    const group=document.createElement('section'); group.dataset.component='pocket-settings-links';
    for(const [name,label,icon] of [['runtime',t('Runtime','Среда'),'terminal'],['android',t('Files & Android','Файлы и Android'),'folder'],['updates',t('Updates','Обновления'),'download']]) {
      const button=document.createElement('button'); button.type='button'; button.dataset.pocketSettings=name;
      button.dataset.slot='tabs-v2-trigger'; button.className='pocket-settings-link';
      const content=document.createElement('span'); content.dataset.slot='tabs-v2-trigger-content';
      const symbol=document.createElement('div'); symbol.dataset.component='icon'; symbol.dataset.size='normal';
      const svg=document.createElementNS('http://www.w3.org/2000/svg','svg'); svg.setAttribute('viewBox','0 0 20 20'); svg.setAttribute('fill','none'); svg.setAttribute('aria-hidden','true'); svg.dataset.slot='icon-svg';
      const use=document.createElementNS('http://www.w3.org/2000/svg','use'); use.setAttribute('href','#opencode-icon-'+icon);
      svg.append(use); symbol.append(svg); content.append(symbol,document.createTextNode(label)); button.append(content);
      const wrapper=document.createElement('span'); wrapper.dataset.slot='tabs-v2-trigger-wrapper'; wrapper.append(button);
      button.onclick=()=>action(name); group.append(wrapper);
    }
    nav.append(group);
  }
  window.pocketMobile = {
    renderPermission,
    openSettings() {
      if(document.querySelector('.settings-v2'))return;
      document.dispatchEvent(new KeyboardEvent('keydown',{key:',',code:'Comma',ctrlKey:true,bubbles:true,cancelable:true}));
      setTimeout(()=>{
        if(document.querySelector('.settings-v2'))return;
        [...document.querySelectorAll('button')].find(b=>/^(Settings|Настройки)$/.test(b.innerText || b.getAttribute('aria-label') || ''))?.click();
      },200);
    }
  };
  // Streaming replies mutate the DOM constantly; check at most once per frame.
  let queued = false;
  new MutationObserver(() => {
    if(queued) return;
    queued = true;
    requestAnimationFrame(() => { queued = false; settingsLinks(); });
  }).observe(document.documentElement,{childList:true,subtree:true});
})();

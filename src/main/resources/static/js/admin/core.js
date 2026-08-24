// ============================================================
// NÚCLEO — TEMA, HELPERS DE API, MODAIS, ESCAPEHTML
// ============================================================

const API = ''; // mesma origem — arquivo servido pelo próprio Spring Boot em /static

// ---------- theme ----------
function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  document.querySelectorAll('.theme-switch button').forEach(b => {
    b.classList.toggle('active', b.dataset.themeOption === theme);
  });
}
function setTheme(theme) { applyTheme(theme); }
applyTheme(window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');

// ---------- helpers ----------
const TOAST_OK_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><circle cx="12" cy="12" r="9"/><path d="M8 12.5l2.5 2.5L16 9.5"/></svg>';
const TOAST_ERR_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16.2v.1"/></svg>';

function toast(msg, isErr) {
  const wrap = document.getElementById('toast-wrap');
  const t = document.createElement('div');
  t.className = 'toast ' + (isErr ? 'err' : 'ok');
  t.innerHTML = `<span class="toast-icon">${isErr ? TOAST_ERR_ICON : TOAST_OK_ICON}</span><span class="toast-text"></span>`;
  t.querySelector('.toast-text').textContent = msg;
  wrap.appendChild(t);
  setTimeout(() => t.remove(), 5000);
}

async function api(method, path, body, isFormData) {
  const token = sessionStorage.getItem('kota-token');
  const opts = { method, headers: {} };
  if (token) opts.headers['Authorization'] = 'Bearer ' + token;
  if (body !== undefined) {
    if (isFormData) {
      opts.body = body;
    } else {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
  }
  const res = await fetch(API + path, opts);

  // Token ausente/expirado/inválido — limpa e manda de volta pro login, sem tentar
  // mostrar um erro genérico pra quem só precisa logar de novo.
  if (res.status === 401) {
    sessionStorage.removeItem('kota-token');
    sessionStorage.removeItem('kota-role');
    sessionStorage.removeItem('kota-name');
    window.location.href = '/login.html';
    throw new Error('Sessão expirada.');
  }

  if (res.status === 204) return null;

  const contentType = res.headers.get('content-type') || '';
  const data = contentType.includes('application/json') ? await res.json() : await res.blob();

  if (!res.ok) {
    const message = (data && data.message) ? data.message : ('Erro HTTP ' + res.status);
    const err = new Error(message);
    err.data = data;
    throw err;
  }
  return data;
}

async function safeCall(fn) {
  try { return await fn(); }
  catch (e) { toast(e.message, true); throw e; }
}

// Link/navegação direta (href, window.open) não manda cabeçalho Authorization — o
// navegador não anexa header customizado numa navegação normal. Por isso todo PDF agora
// baixa via fetch autenticado, vira blob, e só então dispara o download.
async function downloadPdfWithAuth(url, filename, buttonEl) {
  await withButtonLoading(buttonEl, 'Gerando PDF...', async () => {
    const token = sessionStorage.getItem('kota-token');
    const res = await fetch(url, { headers: token ? { 'Authorization': 'Bearer ' + token } : {} });
    if (!res.ok) { toast('Não foi possível baixar o PDF.', true); return; }
    const blob = await res.blob();
    const blobUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(blobUrl);
  });
}

function openModal(html, size) {
  document.getElementById('modal-box-content').innerHTML = html;
  const box = document.getElementById('modal-box');
  box.classList.remove('wide', 'medium');
  if (size === true) box.classList.add('wide');
  else if (size) box.classList.add(size);
  document.getElementById('modal-overlay').style.display = 'flex';
}
function closeModal() {
  document.getElementById('modal-overlay').style.display = 'none';
  document.getElementById('modal-box-content').innerHTML = '';
}
function openModal2(html, size) {
  document.getElementById('modal-box-2-content').innerHTML = html;
  const box = document.getElementById('modal-box-2');
  box.classList.remove('wide', 'medium');
  if (size === true) box.classList.add('wide');
  else if (size) box.classList.add(size);
  document.getElementById('modal-overlay-2').style.display = 'flex';
}
function closeModal2() {
  document.getElementById('modal-overlay-2').style.display = 'none';
  document.getElementById('modal-box-2-content').innerHTML = '';
}

// Necessário porque os modais são montados via innerHTML — texto vindo do banco
// (nome, descrição etc.) precisa ser escapado antes de virar atributo/HTML.
function escapeHtml(str) {
  if (str == null) return '';
  return String(str).replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}


// ============================================================
// DADOS DA EMPRESA
// ============================================================
// ============================================================
// DASHBOARD
// ============================================================
// ============================================================
// DADOS DA EMPRESA — singleton, sem lista, sem criar/desativar. Só existe uma empresa
// usando o sistema, então é formulário direto, não CRUD.
// ============================================================
async function loadCompanySettingsForm() {
  const company = await safeCall(() => api('GET', '/company-settings'));
  document.getElementById('company-name').value = company.name || '';
  document.getElementById('company-cnpj').value = company.cnpj ? maskCnpj(company.cnpj) : '';
  document.getElementById('company-state-registration').value = company.stateRegistration || '';
  document.getElementById('company-email').value = company.email || '';
  document.getElementById('company-phone').value = company.phone ? maskPhone(company.phone) : '';
  document.getElementById('company-address').value = company.address || '';
  document.getElementById('company-neighborhood').value = company.neighborhood || '';
  document.getElementById('company-city').value = company.city || '';
  document.getElementById('company-state').value = company.state || '';
  document.getElementById('company-zip').value = company.zipCode ? maskCep(company.zipCode) : '';
  renderCompanyLogoPreview(company.logoUrl);
}

function renderCompanyLogoPreview(logoUrl) {
  const img = document.getElementById('company-logo-img');
  const empty = document.getElementById('company-logo-empty');
  if (logoUrl) {
    // Cache-busting: sem isso, trocar a logo e recarregar a tela mostraria a antiga —
    // o navegador não sabe que o conteúdo por trás dessa mesma URL mudou.
    img.src = logoUrl + '?t=' + Date.now();
    img.style.display = 'block';
    empty.style.display = 'none';
  } else {
    img.style.display = 'none';
    empty.style.display = 'block';
  }
}

async function saveCompanySettings() {
  const body = {
    name: document.getElementById('company-name').value.trim(),
    cnpj: unmaskDigits(document.getElementById('company-cnpj').value),
    stateRegistration: document.getElementById('company-state-registration').value.trim(),
    email: document.getElementById('company-email').value.trim(),
    phone: unmaskDigits(document.getElementById('company-phone').value),
    address: document.getElementById('company-address').value.trim(),
    neighborhood: document.getElementById('company-neighborhood').value.trim(),
    city: document.getElementById('company-city').value.trim(),
    state: document.getElementById('company-state').value,
    zipCode: unmaskDigits(document.getElementById('company-zip').value)
  };
  if (!body.name) { toast('Nome é obrigatório.', true); return; }

  await safeCall(() => api('PUT', '/company-settings', body));
  toast('Dados da empresa salvos.');
  applyCompanyBranding();
}

// Upload de arquivo não passa pelo api() (que sempre monta JSON) — precisa de
// FormData/multipart, então monta a chamada à parte aqui, só reaproveitando o token.
async function uploadCompanyLogo() {
  const input = document.getElementById('company-logo-input');
  const file = input.files[0];
  if (!file) return;

  const formData = new FormData();
  formData.append('file', file);

  const token = localStorage.getItem('kota-token');
  const res = await fetch(API + '/company-settings/logo', {
    method: 'POST',
    headers: token ? { 'Authorization': 'Bearer ' + token } : {},
    body: formData
  });
  const data = await res.json();

  if (!res.ok) {
    toast(data.message || 'Não foi possível enviar a logo.', true);
    return;
  }

  toast('Logo atualizada.');
  renderCompanyLogoPreview(data.logoUrl);
  input.value = '';
  applyCompanyBranding();
}

// Aplica nome/logo no cabeçalho da barra lateral — chamado no carregamento da página e
// de novo sempre que os dados da empresa mudam, pra não precisar recarregar a página
// pra ver o efeito.
async function applyCompanyBranding() {
  const company = await safeCall(() => api('GET', '/company-settings'));
  const nameEl = document.getElementById('brand-name');
  if (nameEl && company.name) {
    nameEl.textContent = company.name;
    nameEl.title = company.name;
  }

  const logoSlot = document.getElementById('brand-logo-slot');
  if (logoSlot) {
    logoSlot.innerHTML = company.logoUrl
      ? `<img src="${company.logoUrl}?t=${Date.now()}" alt="">`
      : `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 11l9-8 9 8"/><path d="M5 10v10a1 1 0 001 1h4v-6h4v6h4a1 1 0 001-1V10"/></svg>`;
  }
}

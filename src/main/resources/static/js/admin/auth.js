// ============================================================
// AUTENTICAÇÃO E MENU DE CONTA
// ============================================================
// ---------- auth ----------
// Sem token, nem adianta tentar carregar nada — manda direto pro login antes de
// disparar qualquer chamada que ia falhar com 401 de qualquer jeito.
(function checkAuth() {
  const token = sessionStorage.getItem('kota-token');
  if (!token) {
    window.location.href = '/login.html';
    return;
  }
  // Espelho da mesma guarda do representante.html — representante não deve conseguir
  // acessar o painel administrativo direto pela URL.
  if (sessionStorage.getItem('kota-role') !== 'ADMIN') {
    window.location.href = '/representante.html';
    return;
  }
  const name = sessionStorage.getItem('kota-name') || '';
  document.getElementById('admin-identity').textContent = name;
  document.getElementById('account-avatar').textContent = name.trim().charAt(0).toUpperCase() || 'A';
  applyCompanyBranding();
})();

function logout() {
  sessionStorage.removeItem('kota-token');
  sessionStorage.removeItem('kota-role');
  sessionStorage.removeItem('kota-name');
  sessionStorage.removeItem('kota-admin-section');
  window.location.href = '/login.html';
}

// ---------- menu de conta ----------
function toggleAccountMenu(e) {
  e.stopPropagation();
  const menu = document.getElementById('account-menu');
  menu.style.display = menu.style.display === 'none' ? 'block' : 'none';
}

function closeAccountMenu() {
  document.getElementById('account-menu').style.display = 'none';
}

// Fecha ao clicar em qualquer lugar fora do menu — padrão de qualquer dropdown de conta.
document.addEventListener('click', (e) => {
  const wrap = document.getElementById('account-menu');
  const trigger = document.querySelector('.account-trigger');
  if (wrap && wrap.style.display !== 'none' && !wrap.contains(e.target) && e.target !== trigger && !trigger.contains(e.target)) {
    closeAccountMenu();
  }
});

// Troca voluntária de senha — diferente da obrigatória do login.html (essa é sempre
// opcional, o admin decide quando quer trocar, não é forçado). Reaproveita o mesmo
// endpoint (/auth/change-password), que já exige a senha atual pra confirmar.
function openChangePasswordModal() {
  openModal(`
    <h2>Trocar minha senha</h2>
    <div class="field-grid">
      <div><label>Senha atual</label><input type="password" id="cp-current"></div>
      <div><label>Nova senha</label><input type="password" id="cp-new" placeholder="Mínimo 6 caracteres"></div>
      <div><label>Confirmar nova senha</label><input type="password" id="cp-confirm"></div>
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button onclick="submitChangePassword()">Salvar nova senha</button>
      <button class="secondary" onclick="closeModal()">Cancelar</button>
    </div>
  `);
}

async function submitChangePassword() {
  const fieldMap = { currentPassword: 'cp-current', newPassword: 'cp-new' };
  Object.values(fieldMap).forEach(clearFieldError);

  const currentPassword = document.getElementById('cp-current').value;
  const newPassword = document.getElementById('cp-new').value;
  const confirmPassword = document.getElementById('cp-confirm').value;

  if (!currentPassword) { showFieldError('cp-current', 'Informe a senha atual.'); return; }
  if (!newPassword) { showFieldError('cp-new', 'Informe a nova senha.'); return; }
  if (newPassword.length < 6) { showFieldError('cp-new', 'Nova senha deve ter pelo menos 6 caracteres.'); return; }
  if (newPassword !== confirmPassword) { showFieldError('cp-confirm', 'As duas senhas novas precisam ser iguais.'); return; }

  try {
    await api('POST', '/auth/change-password', { currentPassword, newPassword });
  } catch (e) {
    distributeFieldErrors(e.message, fieldMap);
    return;
  }
  toast('Senha trocada com sucesso.');
  closeModal();
}


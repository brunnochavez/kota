// ============================================================
// AUTENTICAÇÃO E MENU DE CONTA
// ============================================================
// ---------- auth ----------
// Sem token, nem adianta tentar carregar nada — manda direto pro login antes de
// disparar qualquer chamada que ia falhar com 401 de qualquer jeito.
(function checkAuth() {
  const token = localStorage.getItem('kota-token');
  if (!token) {
    window.location.href = '/login.html';
    return;
  }
  // Espelho da mesma guarda do representante.html — representante não deve conseguir
  // acessar o painel administrativo direto pela URL.
  if (localStorage.getItem('kota-role') !== 'ADMIN') {
    window.location.href = '/representante.html';
    return;
  }
  const name = localStorage.getItem('kota-name') || '';
  document.getElementById('admin-identity').textContent = name;
  document.getElementById('account-avatar').textContent = name.trim().charAt(0).toUpperCase() || 'A';
  applyCompanyBranding();
})();

function logout() {
  localStorage.removeItem('kota-token');
  localStorage.removeItem('kota-role');
  localStorage.removeItem('kota-name');
  localStorage.removeItem('kota-admin-section');
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
  const currentPassword = document.getElementById('cp-current').value;
  const newPassword = document.getElementById('cp-new').value;
  const confirmPassword = document.getElementById('cp-confirm').value;

  if (!currentPassword || !newPassword) { toast('Preencha todos os campos.', true); return; }
  if (newPassword.length < 6) { toast('Nova senha deve ter pelo menos 6 caracteres.', true); return; }
  if (newPassword !== confirmPassword) { toast('As duas senhas novas precisam ser iguais.', true); return; }

  await safeCall(() => api('POST', '/auth/change-password', { currentPassword, newPassword }));
  toast('Senha trocada com sucesso.');
  closeModal();
}


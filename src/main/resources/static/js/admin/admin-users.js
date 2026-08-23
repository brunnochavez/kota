// ============================================================
// USUÁRIOS ADMINISTRADORES
// ============================================================
// Diferente do acesso de representante (RepresentativeAccessController, ligado a um
// Representative já cadastrado), aqui cada linha É a conta em si — cria acesso completo
// ao painel pra outra pessoa ajudar a gerenciar cotações, sem cadastro de "pessoa" por
// trás. A senha definida na criação/reset é sempre provisória (mustChangePassword=true
// no backend), igual ao fluxo já usado pra representante.

async function loadAdminUsers() {
  const users = await safeCall(() => api('GET', '/users'));
  renderAdminUsersList(users);
}

function renderAdminUsersList(users) {
  const tbody = document.getElementById('admin-users-tbody');
  const empty = document.getElementById('admin-users-empty');
  tbody.innerHTML = '';
  empty.style.display = users.length ? 'none' : 'block';

  users.forEach(u => {
    const tr = document.createElement('tr');
    const statusBadge = u.enabled
      ? '<span class="badge badge-available">Ativo</span>'
      : '<span class="badge badge-draft">Desativado</span>';
    const toggleBtn = u.enabled
      ? `<button class="danger small" onclick="toggleAdminUserEnabled(${u.id}, false, this)">Desativar</button>`
      : `<button class="secondary small" onclick="toggleAdminUserEnabled(${u.id}, true, this)">Reativar</button>`;
    const nameCell = u.name
      ? escapeHtml(u.name)
      : '<span style="color:var(--text-dim)">— sem nome —</span>';
    tr.innerHTML = `
      <td>${nameCell}</td>
      <td>${escapeHtml(u.email)}</td>
      <td>${statusBadge}</td>
      <td class="btn-row">
        <button class="secondary small" onclick="openEditAdminUserNamePopover(${u.id}, '${escapeHtml((u.name || '')).replace(/'/g, "\\'")}', this)">${u.name ? 'Editar nome' : 'Definir nome'}</button>
        <button class="secondary small" onclick="openResetAdminUserPasswordPopover(${u.id}, this)">Redefinir senha</button>
        ${toggleBtn}
      </td>`;
    tbody.appendChild(tr);
  });
}

async function createAdminUser() {
  const nameInput = document.getElementById('au-name');
  const emailInput = document.getElementById('au-email');
  const passwordInput = document.getElementById('au-password');
  clearFieldError('au-name');
  clearFieldError('au-email');
  clearFieldError('au-password');

  try {
    await api('POST', '/users', { name: nameInput.value.trim(), email: emailInput.value.trim(), password: passwordInput.value });
  } catch (e) {
    distributeFieldErrors(e.message, { name: 'au-name', email: 'au-email', password: 'au-password' });
    return;
  }

  toast('Usuário administrador criado — a senha informada é provisória.');
  nameInput.value = '';
  emailInput.value = '';
  passwordInput.value = '';
  loadAdminUsers();
}

// Popover com um campo de texto pra definir/editar o nome de exibição — mesmo padrão
// visual do popover de redefinir senha, ancorado no botão da linha. Nome é o que passa
// a aparecer no "Ver Histórico" da cotação (performedBy) no lugar do e-mail.
function openEditAdminUserNamePopover(id, currentName, anchorEl) {
  const existing = document.getElementById('edit-admin-name-popover');
  if (existing) existing.remove();

  const pop = document.createElement('div');
  pop.className = 'confirm-popover';
  pop.id = 'edit-admin-name-popover';
  pop.innerHTML = `
    <div class="confirm-popover-msg">Nome de exibição</div>
    <input type="text" id="edit-admin-name-input" value="${escapeHtml(currentName)}" placeholder="Nome completo" style="margin-top:8px; width:220px">
    <div class="btn-row" style="justify-content:flex-end; margin-top:10px">
      <button class="secondary small" type="button" id="edit-admin-name-cancel">Cancelar</button>
      <button class="small" type="button" id="edit-admin-name-confirm">Salvar</button>
    </div>`;
  document.body.appendChild(pop);

  const close = () => pop.remove();
  document.getElementById('edit-admin-name-cancel').onclick = close;
  document.getElementById('edit-admin-name-confirm').onclick = async () => {
    const name = document.getElementById('edit-admin-name-input').value.trim();
    if (!name) { toast('Digite um nome.', true); return; }
    try {
      await api('PUT', `/users/${id}/name?name=${encodeURIComponent(name)}`);
    } catch (e) {
      toast(e.message, true);
      return;
    }
    toast('Nome atualizado.');
    close();
    loadAdminUsers();
  };

  const anchor = anchorEl.getBoundingClientRect();
  const popRect = pop.getBoundingClientRect();
  let top = anchor.bottom + 6;
  let left = anchor.right - popRect.width;
  if (top + popRect.height > window.innerHeight - 8) top = anchor.top - popRect.height - 6;
  if (left < 8) left = anchor.left;
  if (left + popRect.width > window.innerWidth - 8) left = window.innerWidth - popRect.width - 8;
  pop.style.top = Math.max(8, top) + 'px';
  pop.style.left = Math.max(8, left) + 'px';

  setTimeout(() => document.addEventListener('mousedown', function handler(e) {
    if (!pop.contains(e.target)) {
      pop.remove();
      document.removeEventListener('mousedown', handler);
    }
  }), 0);
}

// Reaproveita o mesmo popover de confirmação usado em outras telas (fechar cotação,
// reativar cadastro inativo) — evita confirm() nativo, seguindo a convenção do projeto.
function toggleAdminUserEnabled(id, enabled, buttonEl) {
  const action = enabled ? 'reativar' : 'desativar';
  showConfirmPopover(buttonEl, `Tem certeza que deseja ${action} o acesso desse usuário?`, async () => {
    try {
      await api('PUT', `/users/${id}/enabled?enabled=${enabled}`);
    } catch (e) {
      toast(e.message, true);
      return;
    }
    toast(enabled ? 'Usuário reativado.' : 'Usuário desativado.');
    loadAdminUsers();
  });
}

// Popover simples com um campo de senha nova, ancorado no botão "Redefinir senha" —
// mesmo padrão visual do showConfirmPopover, mas com um input em vez de só sim/não.
function openResetAdminUserPasswordPopover(id, anchorEl) {
  const existing = document.getElementById('reset-admin-password-popover');
  if (existing) existing.remove();

  const pop = document.createElement('div');
  pop.className = 'confirm-popover';
  pop.id = 'reset-admin-password-popover';
  pop.innerHTML = `
    <div class="confirm-popover-msg">Nova senha provisória</div>
    <input type="text" id="reset-admin-password-input" placeholder="Mínimo 6 caracteres" style="margin-top:8px; width:220px">
    <div class="btn-row" style="justify-content:flex-end; margin-top:10px">
      <button class="secondary small" type="button" id="reset-admin-password-cancel">Cancelar</button>
      <button class="small" type="button" id="reset-admin-password-confirm">Redefinir</button>
    </div>`;
  document.body.appendChild(pop);

  const close = () => pop.remove();
  document.getElementById('reset-admin-password-cancel').onclick = close;
  document.getElementById('reset-admin-password-confirm').onclick = async () => {
    const newPassword = document.getElementById('reset-admin-password-input').value;
    if (!newPassword || newPassword.length < 6) {
      toast('A senha precisa ter pelo menos 6 caracteres.', true);
      return;
    }
    try {
      await api('PUT', `/users/${id}/password?newPassword=${encodeURIComponent(newPassword)}`);
    } catch (e) {
      toast(e.message, true);
      return;
    }
    toast('Senha redefinida — a pessoa vai precisar trocá-la no próximo login.');
    close();
  };

  const anchor = anchorEl.getBoundingClientRect();
  const popRect = pop.getBoundingClientRect();
  let top = anchor.bottom + 6;
  let left = anchor.right - popRect.width;
  if (top + popRect.height > window.innerHeight - 8) top = anchor.top - popRect.height - 6;
  if (left < 8) left = anchor.left;
  if (left + popRect.width > window.innerWidth - 8) left = window.innerWidth - popRect.width - 8;
  pop.style.top = Math.max(8, top) + 'px';
  pop.style.left = Math.max(8, left) + 'px';

  setTimeout(() => document.addEventListener('mousedown', function handler(e) {
    if (!pop.contains(e.target)) {
      pop.remove();
      document.removeEventListener('mousedown', handler);
    }
  }), 0);
}

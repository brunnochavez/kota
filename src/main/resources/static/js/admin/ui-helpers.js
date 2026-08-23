// ============================================================
// PAGINAÇÃO, ERROS DE CAMPO, MÁSCARAS, DATAS
// ============================================================
// ============================================================
// PAGINAÇÃO — genérica, client-side, 10 por página. Reaproveitada em toda lista que já
// carrega o array inteiro na memória (não busca de novo no servidor a cada página, só
// corta o array já carregado). idPrefix precisa bater com o bloco de controles gerado
// por paginationControlsHtml() no HTML daquela lista.
// ============================================================
const DEFAULT_PAGE_SIZE = 10;

function paginateSlice(array, page, pageSize) {
  const totalPages = Math.max(Math.ceil(array.length / pageSize), 1);
  const safePage = Math.min(Math.max(page, 0), totalPages - 1);
  return { items: array.slice(safePage * pageSize, safePage * pageSize + pageSize), page: safePage, totalPages };
}

function paginationControlsHtml(idPrefix) {
  return `<div class="btn-row" id="${idPrefix}-pagination" style="justify-content:space-between; margin-top:10px; display:none">
    <div style="color:var(--text-dim); font-size:12px" id="${idPrefix}-page-info">—</div>
    <div class="btn-row">
      <button class="secondary small" id="${idPrefix}-prev-btn">← Anterior</button>
      <button class="secondary small" id="${idPrefix}-next-btn">Próxima →</button>
    </div>
  </div>`;
}

function updatePaginationControls(idPrefix, page, totalPages, totalItems, onChange) {
  const wrap = document.getElementById(idPrefix + '-pagination');
  if (!wrap) return;
  wrap.style.display = totalPages > 1 ? 'flex' : 'none';
  document.getElementById(idPrefix + '-page-info').textContent = `Página ${page + 1} de ${totalPages}`;
  const prevBtn = document.getElementById(idPrefix + '-prev-btn');
  const nextBtn = document.getElementById(idPrefix + '-next-btn');
  prevBtn.disabled = page === 0;
  nextBtn.disabled = page >= totalPages - 1;
  prevBtn.onclick = () => onChange(page - 1);
  nextBtn.onclick = () => onChange(page + 1);
}

// Mostra o erro em vermelho logo abaixo do campo, em vez de só um toast (que fica
// escondido atrás do modal quando o erro acontece durante um cadastro/edição).
function showFieldError(inputId, message) {
  clearFieldError(inputId);
  const input = document.getElementById(inputId);
  if (!input) { toast(message, true); return; }
  input.classList.add('input-error');
  const err = document.createElement('div');
  err.className = 'field-error';
  err.id = inputId + '-error';
  err.textContent = message;
  input.insertAdjacentElement('afterend', err);
}
function clearFieldError(inputId) {
  const existing = document.getElementById(inputId + '-error');
  if (existing) existing.remove();
  const input = document.getElementById(inputId);
  if (input) input.classList.remove('input-error');
  closeOkPopover();
}

// Campos onde o erro aparece num popover "OK" ancorado perto do campo, em vez do
// texto vermelho inserido embaixo — usado nos campos de data que ficam dentro de um
// grupo flex compartilhado com o campo de hora, onde inserir texto ali dentro
// empurrava/quebrava o layout dos campos vizinhos. distributeFieldErrors() consulta
// essa lista pra decidir qual dos dois jeitos usar, campo por campo.
const POPOVER_ERROR_FIELDS = new Set(['mq-expiration-date', 'qd-expiration-date', 'extend-expiration-date', 'republish-expiration-date']);

function showFieldErrorSmart(inputId, message) {
  const input = document.getElementById(inputId);
  if (input && POPOVER_ERROR_FIELDS.has(inputId)) {
    showOkPopover(input, message);
    input.classList.add('input-error');
    return;
  }
  showFieldError(inputId, message);
}

// Popover com só um botão "OK" pra fechar — mesmo padrão de posicionamento do
// showConfirmPopover (perto do campo, ajustando pra não estourar a borda da tela),
// mas pra avisos que só pedem "entendi", não uma decisão de sim/não.
function showOkPopover(anchorEl, messageHtml) {
  closeOkPopover();

  const pop = document.createElement('div');
  pop.className = 'confirm-popover';
  pop.id = 'ok-popover';
  pop.innerHTML = `
    <div class="confirm-popover-msg" style="color:var(--danger)">${messageHtml}</div>
    <div class="btn-row" style="justify-content:flex-end; margin-top:10px">
      <button class="small" type="button">OK</button>
    </div>`;
  document.body.appendChild(pop);

  pop.querySelector('button').onclick = closeOkPopover;

  const anchor = anchorEl.getBoundingClientRect();
  const popRect = pop.getBoundingClientRect();
  let top = anchor.bottom + 6;
  let left = anchor.left;
  if (top + popRect.height > window.innerHeight - 8) top = anchor.top - popRect.height - 6;
  if (left + popRect.width > window.innerWidth - 8) left = window.innerWidth - popRect.width - 8;
  pop.style.top = Math.max(8, top) + 'px';
  pop.style.left = Math.max(8, left) + 'px';

  // setTimeout pra esse mesmo clique (o que gerou o erro) não ser lido como "clique fora"
  setTimeout(() => document.addEventListener('mousedown', handleOutsideOkPopoverClick), 0);
}

function handleOutsideOkPopoverClick(e) {
  const pop = document.getElementById('ok-popover');
  if (pop && !pop.contains(e.target)) closeOkPopover();
}

function closeOkPopover() {
  const pop = document.getElementById('ok-popover');
  if (pop) pop.remove();
  document.removeEventListener('mousedown', handleOutsideOkPopoverClick);
}

// ---------- máscaras (CNPJ, telefone, valor monetário) ----------
function maskCnpj(raw) {
  const d = (raw || '').replace(/\D/g, '').slice(0, 14);
  let out = d;
  if (d.length > 2) out = d.slice(0, 2) + '.' + d.slice(2);
  if (d.length > 5) out = d.slice(0, 2) + '.' + d.slice(2, 5) + '.' + d.slice(5);
  if (d.length > 8) out = d.slice(0, 2) + '.' + d.slice(2, 5) + '.' + d.slice(5, 8) + '/' + d.slice(8);
  if (d.length > 12) out = d.slice(0, 2) + '.' + d.slice(2, 5) + '.' + d.slice(5, 8) + '/' + d.slice(8, 12) + '-' + d.slice(12);
  return out;
}
function maskPhone(raw) {
  const d = (raw || '').replace(/\D/g, '').slice(0, 11);
  if (d.length <= 10) {
    let out = d;
    if (d.length > 2) out = '(' + d.slice(0, 2) + ') ' + d.slice(2);
    if (d.length > 6) out = '(' + d.slice(0, 2) + ') ' + d.slice(2, 6) + '-' + d.slice(6);
    return out;
  }
  return '(' + d.slice(0, 2) + ') ' + d.slice(2, 7) + '-' + d.slice(7);
}
function unmaskDigits(value) {
  return (value || '').replace(/\D/g, '');
}
function maskCep(raw) {
  const d = (raw || '').replace(/\D/g, '').slice(0, 8);
  return d.length > 5 ? d.slice(0, 5) + '-' + d.slice(5) : d;
}
function maskCurrencyInput(raw) {
  let d = (raw || '').replace(/\D/g, '');
  if (!d) return '';
  d = d.replace(/^0+(?=\d)/, '');
  while (d.length < 3) d = '0' + d;
  const cents = d.slice(-2);
  let intPart = d.slice(0, -2);
  intPart = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return intPart + ',' + cents;
}
function unmaskCurrencyToNumber(value) {
  if (!value) return null;
  const normalized = String(value).replace(/\./g, '').replace(',', '.');
  const n = parseFloat(normalized);
  return isNaN(n) ? null : n;
}
function formatCurrencyFromNumber(n) {
  if (n === null || n === undefined || n === '') return '';
  const parts = Number(n).toFixed(2).split('.');
  parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return parts[0] + ',' + parts[1];
}

// Usado pelas 4 telas de cadastro (Produto, Representante, Fornecedor, Grupo):
// tenta criar/atualizar; se o backend disser que existe um cadastro INATIVO com a
// mesma chave (CNPJ/barcode/e-mail), oferece reativá-lo em vez de falhar com erro cru de banco.
// fieldMap (opcional): { nomeDoCampoNoBackend: 'id-do-input-no-html' } — distribui cada erro de
// validação pro campo certo (o backend manda "campo: mensagem; campo2: mensagem2" quando são
// vários campos inválidos ao mesmo tempo).
async function saveWithReactivation(createOrUpdateFn, reactivateFn, fieldMap, buttonEl, onSuccess) {
  if (fieldMap) Object.values(fieldMap).forEach(clearFieldError);
  try {
    await createOrUpdateFn();
    onSuccess();
  } catch (e) {
    if (e.data && e.data.existingId) {
      showConfirmPopover(buttonEl, e.message + '<br><br>Deseja reativar esse cadastro com os dados que você acabou de digitar?', async () => {
        await safeCall(() => reactivateFn(e.data.existingId));
        onSuccess();
      });
      return;
    }
    if (fieldMap) {
      distributeFieldErrors(e.message, fieldMap);
    } else {
      toast(e.message, true);
    }
  }
}

// Separa "campo: mensagem; campo2: mensagem2" (formato do GlobalExceptionHandler pra erros de
// validação com vários campos) e mostra cada mensagem embaixo do input certo. Se a mensagem não
// tiver esse formato (ex: erro de duplicidade, que é uma frase só), cai no primeiro campo do mapa.
function distributeFieldErrors(message, fieldMap) {
  const parts = message.split(';').map(s => s.trim()).filter(Boolean);
  let matchedAny = false;
  parts.forEach(part => {
    const idx = part.indexOf(':');
    if (idx === -1) return;
    const fieldName = part.slice(0, idx).trim();
    const msg = part.slice(idx + 1).trim();
    const inputId = fieldMap[fieldName];
    if (inputId) {
      showFieldErrorSmart(inputId, msg);
      matchedAny = true;
    }
  });
  if (!matchedAny) {
    const firstFieldId = Object.values(fieldMap)[0];
    if (firstFieldId) showFieldErrorSmart(firstFieldId, message);
    else toast(message, true);
  }
}

function fmtDate(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('pt-BR');
}

// Só a data, sem hora — usado onde o horário exato não importa (ex: ponto de compra,
// que é uma estimativa de dia, não um horário marcado).
function fmtDateOnly(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('pt-BR');
}

function toDatetimeLocal(iso) {
  if (!iso) return '';
  return iso.substring(0, 16);
}

// Junta os dois inputs (data + hora) num datetime-local string ("YYYY-MM-DDTHH:mm"),
// formato que o backend espera. Prazo é sempre opcional: sem data preenchida, retorna
// null. Data preenchida sem hora vira 23:59 (fim do dia) — mais intuitivo que forçar
// escolher hora, e ainda cobre o dia inteiro. Qualquer horário é aceito, sem
// arredondamento pra hora cheia.
function getExpirationValue(prefix) {
  const dateVal = document.getElementById(prefix + '-date').value;
  if (!dateVal) return null;
  const timeVal = document.getElementById(prefix + '-time').value || '23:59';
  return `${dateVal}T${timeVal}`;
}

// Preenche os dois inputs a partir de um datetime-local/ISO vindo do backend.
function setExpirationValue(prefix, isoValue) {
  const dateInput = document.getElementById(prefix + '-date');
  const timeInput = document.getElementById(prefix + '-time');
  if (!isoValue) { dateInput.value = ''; timeInput.value = ''; return; }
  const [datePart, timePart] = toDatetimeLocal(isoValue).split('T');
  dateInput.value = datePart;
  timeInput.value = timePart;
}

function clearExpirationValue(prefix) {
  document.getElementById(prefix + '-date').value = '';
  document.getElementById(prefix + '-time').value = '';
}


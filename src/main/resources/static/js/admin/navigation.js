// ============================================================
// NAVEGAÇÃO ENTRE SEÇÕES
// ============================================================
// ---------- navigation ----------
document.querySelectorAll('.nav-item').forEach(item => {
  item.addEventListener('click', () => switchToSection(item.dataset.section));
});

// Fonte única de verdade pra trocar de seção — reaproveitada pelo clique nos itens de
// menu E por goToSection(), que agora também precisa funcionar pra seções sem item
// correspondente na nav principal (ex: "Dados da Empresa", que só é acessível pelo
// menu de conta).
//
// Trocar de seção só esconde a div antiga (display:none) e mostra a nova — nunca destrói
// e reconstrói nada. Sem isso, texto digitado num campo de busca ou num formulário ficava
// exatamente do jeito que a pessoa deixou, mesmo depois de navegar pra outro lugar e
// voltar horas depois. Por isso, antes de trocar, zera os campos "voláteis" da seção que
// está sendo deixada — buscas viram texto vazio de novo, e o formulário de "Iniciar uma
// Cotação" (o mais expressivo — nome, itens, tudo) volta pro estado em branco.
function switchToSection(section) {
  const previousSection = document.querySelector('.section.active');
  const previousId = previousSection ? previousSection.id.replace('section-', '') : null;
  if (previousId && previousId !== section) {
    resetSectionVolatileInputs(previousId);
  }

  document.querySelectorAll('.nav-item').forEach(i => i.classList.toggle('active', i.dataset.section === section));
  document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
  document.getElementById('section-' + section).classList.add('active');
  loadSectionData(section);
  sessionStorage.setItem('kota-admin-section', section);
}

// Campo de busca de cada listagem — cada um só filtra a própria tela, não faz sentido
// nenhum reaparecer preenchido numa visita futura.
const SECTION_SEARCH_INPUT_IDS = {
  products: 'product-search',
  representatives: 'rep-search',
  suppliers: 'supplier-search'
};

function resetSectionVolatileInputs(section) {
  const searchId = SECTION_SEARCH_INPUT_IDS[section];
  if (searchId) {
    const el = document.getElementById(searchId);
    if (el) el.value = '';
  }
  if (section === 'quotation-start') resetQuotationStartForm();
}

// Limpa os dois formulários de "Iniciar uma Cotação" (Importar CSV e Criar Manualmente)
// por completo — incluindo as linhas de item já adicionadas, que não são um <input>
// simples de resetar, são elementos criados na hora. Também volta pro método padrão
// (Importar), pra sempre abrir do mesmo jeito da primeira vez.
function resetQuotationStartForm() {
  document.getElementById('import-name').value = '';
  document.getElementById('import-group').value = '';
  clearExpirationValue('import-expiration');
  document.getElementById('import-sales-projection').value = '';
  document.getElementById('import-file').value = '';
  document.getElementById('import-mapping').style.display = 'none';
  document.getElementById('import-headers-list').innerHTML = '';

  document.getElementById('mq-name').value = '';
  document.getElementById('mq-group').value = '';
  clearExpirationValue('mq-expiration');
  document.getElementById('mq-sales-projection').value = '';
  document.getElementById('mq-items').innerHTML = '';
  manualItemCount = 0;

  showStartMethod('import');
}

function goToSection(section) {
  switchToSection(section);
}

document.querySelectorAll('#status-filter-tabs .tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('#status-filter-tabs .tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    currentStatusFilter = tab.dataset.status;
    document.getElementById('quotations-list-title').textContent = STATUS_LABELS_PLURAL[currentStatusFilter];
    renderQuotationsList();
  });
});

function showStartMethod(method) {
  document.getElementById('panel-import').style.display = method === 'import' ? 'block' : 'none';
  document.getElementById('panel-manual').style.display = method === 'manual' ? 'block' : 'none';
  document.getElementById('btn-start-import').className = method === 'import' ? '' : 'secondary';
  document.getElementById('btn-start-manual').className = method === 'manual' ? '' : 'secondary';
}

function loadSectionData(section) {
  if (section === 'dashboard') loadDashboard();
  if (section === 'products') loadProducts();
  if (section === 'representatives') loadRepresentatives();
  if (section === 'suppliers') loadSuppliers();
  if (section === 'groups') loadGroups();
  if (section === 'product-groups') loadProductGroups();
  if (section === 'quotation-start') { loadGroupsForSelects(); loadProductsForManualItems(); }
  if (section === 'quotation-reports') loadQuotations();
  if (section === 'reports') loadReportsFilters();
  if (section === 'reorder-points') loadReorderPointReport();
  if (section === 'company-settings') loadCompanySettingsForm();
}

function goToQuotationsFiltered(status) {
  goToSection('quotation-reports');
  const tab = document.querySelector(`#status-filter-tabs .tab[data-status="${status}"]`);
  if (tab) tab.click();
}


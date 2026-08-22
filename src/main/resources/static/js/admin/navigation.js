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
function switchToSection(section) {
  document.querySelectorAll('.nav-item').forEach(i => i.classList.toggle('active', i.dataset.section === section));
  document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
  document.getElementById('section-' + section).classList.add('active');
  loadSectionData(section);
  sessionStorage.setItem('kota-admin-section', section);
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


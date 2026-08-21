// ============================================================
// INICIALIZAÇÃO
// ============================================================
// ---------- init ----------
// Restaura a seção de onde a página foi atualizada (F5) — sem isso, dar refresh em
// qualquer tela sempre jogava de volta pro Dashboard, perdendo o contexto de onde
// o admin estava trabalhando.
(function initSection() {
  const savedSection = localStorage.getItem('kota-admin-section');
  const validSections = Array.from(document.querySelectorAll('.nav-item')).map(el => el.dataset.section);
  if (savedSection && validSections.includes(savedSection)) {
    goToSection(savedSection);
  } else {
    loadDashboard();
  }
})();

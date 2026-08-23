// ============================================================
// DASHBOARD DE ECONOMIA (SPEND SAVINGS)
// ============================================================
// "Quanto economizei esse mês comparando o menor lance com a média dos lances",
// por grupo de fornecedores. Sem filtro de data, o backend já assume o mês
// corrente (dia 1 até agora) — os campos De/Até aqui só existem pra quem quiser
// outro período; "Mês atual" reseta os dois e recarrega.

function setSpendSavingsCurrentMonth() {
  document.getElementById('spend-savings-from').value = '';
  document.getElementById('spend-savings-to').value = '';
  loadSpendSavings();
}

async function loadSpendSavings() {
  const from = document.getElementById('spend-savings-from').value;
  const to = document.getElementById('spend-savings-to').value;
  const query = `?${from ? `from=${from}&` : ''}${to ? `to=${to}&` : ''}`;

  const summary = await safeCall(() => api('GET', `/quotations/spend-savings${query}`));

  document.getElementById('spend-savings-total').textContent = 'R$ ' + formatCurrencyFromNumber(summary.totalSavings);
  document.getElementById('spend-savings-spend').textContent = 'R$ ' + formatCurrencyFromNumber(summary.totalSpend);
  document.getElementById('spend-savings-item-count').textContent = summary.itemCount;

  const table = document.getElementById('spend-savings-table');
  const tbody = document.getElementById('spend-savings-tbody');
  const empty = document.getElementById('spend-savings-empty');

  if (!summary.byGroup.length) {
    table.style.display = 'none';
    empty.style.display = 'block';
    return;
  }

  table.style.display = '';
  empty.style.display = 'none';
  tbody.innerHTML = summary.byGroup.map(row => `
    <tr>
      <td>${escapeHtml(row.supplierGroupName)}</td>
      <td style="text-align:right; color:var(--success); font-weight:600">R$ ${formatCurrencyFromNumber(row.totalSavings)}</td>
      <td style="text-align:right">R$ ${formatCurrencyFromNumber(row.totalSpend)}</td>
      <td style="text-align:center">${row.itemCount}</td>
    </tr>`).join('');
}

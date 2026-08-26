// ============================================================
// IMPORTAÇÃO DE CSV
// ============================================================
// --- CSV import ---
async function importFile(withMapping) {
  const fileInput = document.getElementById('import-file');
  if (!fileInput.files.length) { toast('Escolha um arquivo primeiro.', true); return; }
  const name = document.getElementById('import-name').value.trim();
  if (!name) { toast('Informe um nome para a cotação.', true); return; }
  const groupId = document.getElementById('import-group').value;
  const expiration = getExpirationValue('import-expiration');
  const salesProjection = document.getElementById('import-sales-projection').value;
  const includeCost = document.getElementById('import-include-cost').checked;

  const formData = new FormData();
  formData.append('file', fileInput.files[0]);

  let url = `/quotations/import?name=${encodeURIComponent(name)}&includeCostPrices=${includeCost}`;
  if (groupId) url += `&supplierGroupId=${groupId}`;
  if (expiration) url += `&expirationDate=${encodeURIComponent(expiration)}`;
  if (salesProjection) url += `&defaultSalesProjectionDays=${salesProjection}`;
  if (withMapping) {
    const desc = document.getElementById('map-description').value;
    const barcode = document.getElementById('map-barcode').value;
    const qty = document.getElementById('map-quantity').value;
    url += `&descriptionColumn=${desc}&barcodeColumn=${barcode}&quantityColumn=${qty}`;
    // "— não usar —" (value vazio) é uma escolha válida mesmo com o checkbox marcado —
    // preço de custo nunca é obrigatório, só não manda o parâmetro nesse caso.
    const costEl = document.getElementById('map-cost');
    if (costEl && costEl.value !== '') url += `&costColumn=${costEl.value}`;
  }

  const result = await safeCall(() => api('POST', url, formData, true));

  if (result.needsMapping) {
    document.getElementById('import-mapping').style.display = 'block';
    const list = document.getElementById('import-headers-list');
    const columnOptions = result.headersFound.map((h, i) => `<option value="${i}">${i} — ${h}</option>`).join('');
    // Coluna de custo só aparece no mapeamento se o checkbox "incluir preço de custo"
    // estava marcado no momento do envio — sem isso, nem faz sentido perguntar qual
    // coluna usar. "— não usar —" continua disponível mesmo assim: o admin pode ter
    // marcado o checkbox e perceber, já na hora de mapear, que essa planilha específica
    // não tem coluna de custo nenhuma.
    const costFieldHtml = includeCost
      ? `<div><label>Coluna de preço de custo (opcional)</label><select id="map-cost"><option value="">— não usar —</option>${columnOptions}</select></div>`
      : '';
    list.innerHTML = `
      <div><label>Coluna de descrição</label><select id="map-description">${columnOptions}</select></div>
      <div><label>Coluna de código de barras</label><select id="map-barcode">${columnOptions}</select></div>
      <div><label>Coluna de quantidade</label><select id="map-quantity">${columnOptions}</select></div>
      ${costFieldHtml}`;
    toast('Cabeçalho novo — informe o mapeamento das colunas.');
  } else {
    toast('Cotação #' + result.quotation.id + ' importada com sucesso.');
    document.getElementById('import-mapping').style.display = 'none';
    document.getElementById('import-name').value = '';
    document.getElementById('import-group').value = '';
    clearExpirationValue('import-expiration');
    document.getElementById('import-sales-projection').value = '';
    document.getElementById('import-include-cost').checked = false;
    fileInput.value = '';
    goToSection('quotation-reports');
  }
}

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
  const expiration = document.getElementById('import-expiration').value;
  const salesProjection = document.getElementById('import-sales-projection').value;

  const formData = new FormData();
  formData.append('file', fileInput.files[0]);

  let url = `/quotations/import?name=${encodeURIComponent(name)}`;
  if (groupId) url += `&supplierGroupId=${groupId}`;
  if (expiration) url += `&expirationDate=${encodeURIComponent(expiration)}`;
  if (salesProjection) url += `&defaultSalesProjectionDays=${salesProjection}`;
  if (withMapping) {
    const desc = document.getElementById('map-description').value;
    const barcode = document.getElementById('map-barcode').value;
    const qty = document.getElementById('map-quantity').value;
    url += `&descriptionColumn=${desc}&barcodeColumn=${barcode}&quantityColumn=${qty}`;
  }

  const result = await safeCall(() => api('POST', url, formData, true));

  if (result.needsMapping) {
    document.getElementById('import-mapping').style.display = 'block';
    const list = document.getElementById('import-headers-list');
    list.innerHTML = `
      <div><label>Coluna de descrição</label><select id="map-description">${result.headersFound.map((h, i) => `<option value="${i}">${i} — ${h}</option>`).join('')}</select></div>
      <div><label>Coluna de código de barras</label><select id="map-barcode">${result.headersFound.map((h, i) => `<option value="${i}">${i} — ${h}</option>`).join('')}</select></div>
      <div><label>Coluna de quantidade</label><select id="map-quantity">${result.headersFound.map((h, i) => `<option value="${i}">${i} — ${h}</option>`).join('')}</select></div>`;
    toast('Cabeçalho novo — informe o mapeamento das colunas.');
  } else {
    toast('Cotação #' + result.quotation.id + ' importada com sucesso.');
    document.getElementById('import-mapping').style.display = 'none';
    document.getElementById('import-name').value = '';
    document.getElementById('import-group').value = '';
    document.getElementById('import-expiration').value = '';
    document.getElementById('import-sales-projection').value = '';
    fileInput.value = '';
    goToSection('quotation-reports');
  }
}


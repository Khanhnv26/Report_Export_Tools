const { createApp } = Vue;

const emptyConfig = () => ({ outputMode: 'TABLE', templateId: '', headerRow: 0, outputColumns: [], filters: [],
  sortField: '', sortDirection: 'ASC', mapping: {},
  baseTable: { dbId: '', tableName: '', alias: '', columns: [], filterColumn: '', fromDate: '', toDate: '' }, joins: [] });
const copy = value => JSON.parse(JSON.stringify(value));

createApp({
  data() {
    return { view: 'report', step: 1, steps: ['Nguồn dữ liệu', 'Cột đầu ra', 'Lọc & sắp xếp', 'Xem trước & xuất'],
      config: emptyConfig(), databases: [], databaseTypes: [], baseTables: [], baseColumns: [],
      savedProfiles: {}, selectedProfile: '', templateFileName: '', excelColumns: [],
      preview: null, previewSignature: '', previewLoading: false, previewRequestId: 0,
      metadataLoading: 0, connectLoading: false, uploadLoading: false, exportLoading: false,
      job: null, jobSignature: '', jobPollingError: '', jobPollSequence: 0, dialog: null, toasts: [], showConnectModal: false,
      newDb: {}, fieldErrors: {}, errors: {}, draggedColumn: null, sourceSearch: '', outputSearch: '',
      types: [{ id: 'AUTO', label: 'Tự động' }, { id: 'TEXT', label: 'Chữ' }, { id: 'NUMBER', label: 'Số' },
        { id: 'DATE', label: 'Ngày' }, { id: 'DATETIME', label: 'Ngày & giờ' }, { id: 'BOOLEAN', label: 'Đúng / sai' }],
      formats: [{ id: 'GENERAL', label: 'Mặc định' }, { id: 'INTEGER', label: '1.234' },
        { id: 'DECIMAL', label: '1.234,56' }, { id: 'MONEY', label: 'Tiền · 2 số lẻ' },
        { id: 'DATE', label: 'dd/mm/yyyy' }, { id: 'DATETIME', label: 'dd/mm/yyyy hh:mm:ss' }],
      operators: [{ id: 'CONTAINS', label: 'Chứa' }, { id: 'EQ', label: 'Bằng' }, { id: 'NE', label: 'Khác' },
        { id: 'GT', label: 'Lớn hơn' }, { id: 'GTE', label: 'Từ' }, { id: 'LT', label: 'Nhỏ hơn' },
        { id: 'LTE', label: 'Đến' }, { id: 'EMPTY', label: 'Trống' }, { id: 'NOT_EMPTY', label: 'Có dữ liệu' }] };
  },
  computed: {
    selectedDatabaseType() { return this.databaseTypes.find(type => type.id === this.newDb.dbType) || { databaseLabel: 'Database', supportsSchema: false }; },
    allSelectedFields() {
      const base = this.config.baseTable;
      const fields = (base.columns || []).map(column => `${base.alias || base.tableName}.${column}`);
      this.config.joins.forEach(join => (join.columns || []).forEach(column => fields.push(`${join.alias || join.tableName}.${column}`)));
      return fields;
    },
    filteredBaseColumns() { return this.baseColumns.filter(column => column.name.toLowerCase().includes(this.sourceSearch.toLowerCase())); },
    availableOutputFields() { return this.allSelectedFields.filter(field => field.toLowerCase().includes(this.outputSearch.toLowerCase())); },
    currentSignature() { return JSON.stringify(this.requestConfig()); },
    previewStale() { return !!this.preview && this.previewSignature !== this.currentSignature; },
    activeJob() { return this.job && !['READY', 'FAILED', 'CANCELLED'].includes(this.job.state); },
    canExport() { return this.preview && !this.previewStale && !this.previewLoading && !this.activeJob && !this.exportLoading; },
    isTemplate() { return this.config.outputMode === 'TEMPLATE'; }
  },
  watch: {
    currentSignature(value) {
      if (this._previewController && value !== this._previewSignature) {
        this._previewController.abort(); this.previewRequestId++; this.previewLoading = false;
      }
    }
  },
  mounted() {
    this._metadataCache = new Map(); this._metadataRequests = new Map();
    this.loadSavedProfilesFromStorage(); this.fetchDatabaseTypes(); this.fetchDatabases();
    this._keys = event => this.onDialogKey(event);
    document.addEventListener('keydown', this._keys);
    try { const id = localStorage.getItem('report_active_job'); if (id) this.pollJob(id); } catch (_) { }
  },
  beforeUnmount() {
    this._previewController?.abort(); clearTimeout(this._jobTimer); document.removeEventListener('keydown', this._keys);
  },
  methods: {
    requestConfig() {
      const config = copy(this.config);
      config.joins = config.joins.map(({ availableTables, availableColumns, ...join }) => join);
      return config;
    },
    async api(url, options = {}) {
      const response = await fetch(url, options);
      let payload;
      try { payload = await response.json(); } catch (_) { throw new Error('Máy chủ trả về dữ liệu không hợp lệ. Hãy kiểm tra kết nối và thử lại.'); }
      if (!response.ok) throw new Error(payload.message || `Không thực hiện được yêu cầu (${response.status}).`);
      return payload.data;
    },
    post(url, body, options = {}) { return this.api(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), ...options }); },
    toast(message, tone = 'success') {
      const id = Date.now() + Math.random(); this.toasts.push({ id, message, tone });
      setTimeout(() => { this.toasts = this.toasts.filter(toast => toast.id !== id); }, 6000);
    },
    focusModal() {
      this.$nextTick(() => {
        const modal = document.querySelector('[role="dialog"]');
        modal?.querySelector('[autofocus], input, button, select')?.focus();
      });
    },
    showError(title, error, retry = null) {
      if (this.dialog) { this._errorQueue ||= []; this._errorQueue.push({ title, error, retry }); return; }
      this._previousFocus = document.activeElement; this._retry = retry;
      this.dialog = { kind: 'error', title, message: error.message || String(error), retry: !!retry };
      this.focusModal();
    },
    ask(title, message, options = {}) {
      this._previousFocus = document.activeElement;
      return new Promise(resolve => {
        this._dialogResolve = resolve;
        this.dialog = { kind: options.input ? 'input' : 'confirm', title, message,
          value: options.value || '', confirm: options.confirm || 'Xác nhận', destructive: !!options.destructive, error: '' };
        this.focusModal();
      });
    },
    closeDialog(result = false) {
      if (this.dialog?.kind === 'input' && result === true) {
        if (!this.dialog.value.trim()) { this.dialog.error = 'Vui lòng nhập tên mẫu.'; return; }
        result = this.dialog.value.trim();
      }
      const resolve = this._dialogResolve; this._dialogResolve = null; this.dialog = null;
      if (resolve) resolve(result);
      this.$nextTick(() => {
        if (this.showConnectModal) this.focusModal(); else this._previousFocus?.focus();
        const next = this._errorQueue?.shift(); if (next) this.showError(next.title, next.error, next.retry);
      });
    },
    retryDialog() { const retry = this._retry; this.closeDialog(); if (retry) retry(); },
    onDialogKey(event) {
      if (!this.dialog && !this.showConnectModal) return;
      if (event.key === 'Escape') {
        event.preventDefault(); if (this.dialog) this.closeDialog(); else if (!this.connectLoading) this.closeConnection();
      }
      if (event.key === 'Tab') {
        const modal = document.querySelector('[role="dialog"]');
        const controls = Array.from(modal?.querySelectorAll('button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex="0"]') || []);
        if (!controls.length) return;
        if (event.shiftKey && document.activeElement === controls[0]) { event.preventDefault(); controls.at(-1).focus(); }
        if (!event.shiftKey && document.activeElement === controls.at(-1)) { event.preventDefault(); controls[0].focus(); }
      }
    },
    async fetchDatabaseTypes() {
      try { this.databaseTypes = await this.api('/api/database-types'); }
      catch (error) { this.showError('Không tải được loại database', error, () => this.fetchDatabaseTypes()); }
    },
    async fetchDatabases() {
      try { this.databases = await this.api('/api/databases'); }
      catch (error) { this.showError('Không tải được kết nối', error, () => this.fetchDatabases()); }
    },
    async metadata(url, force = false) {
      this._metadataCache ||= new Map(); this._metadataRequests ||= new Map();
      const cached = this._metadataCache.get(url);
      if (!force && cached && Date.now() - cached.time < 60000) return cached.value;
      if (!force && this._metadataRequests.has(url)) return this._metadataRequests.get(url);
      this.metadataLoading++;
      const request = this.api(url).then(value => { this._metadataCache.set(url, { time: Date.now(), value }); return value; });
      this._metadataRequests.set(url, request);
      try { return await request; }
      finally { this.metadataLoading--; if (this._metadataRequests.get(url) === request) this._metadataRequests.delete(url); }
    },
    sourceUrl(source) { return `/api/databases/${encodeURIComponent(source.dbId)}/tables`; },
    async onBaseDbChange() {
      const base = this.config.baseTable; const id = base.dbId;
      base.tableName = ''; base.columns = []; this.baseTables = []; this.baseColumns = [];
      base.filterColumn = ''; base.fromDate = ''; base.toDate = ''; this.syncOutputColumns();
      if (!id) return;
      try { const tables = await this.metadata(this.sourceUrl(base)); if (base.dbId === id) this.baseTables = tables; }
      catch (error) { this.showError('Không đọc được bảng', error, () => this.onBaseDbChange()); }
    },
    async onBaseTableChange() {
      const base = this.config.baseTable; const key = `${base.dbId}/${base.tableName}`;
      base.columns = []; this.baseColumns = []; this.sourceSearch = '';
      base.filterColumn = ''; base.fromDate = ''; base.toDate = ''; this.syncOutputColumns();
      if (!base.dbId || !base.tableName) return;
      try {
        const columns = await this.metadata(`${this.sourceUrl(base)}/${encodeURIComponent(base.tableName)}/columns`);
        if (`${base.dbId}/${base.tableName}` !== key) return;
        this.baseColumns = columns; base.columns = columns.slice(0, 200).map(column => column.name); this.syncOutputColumns();
      } catch (error) { this.showError('Không đọc được cột', error, () => this.onBaseTableChange()); }
    },
    async addJoinTable() {
      this.config.joins.push({ dbId: '', tableName: '', alias: `nguon_${this.config.joins.length + 1}`,
        columns: [], sourceKey: '', targetKey: '', availableTables: [], availableColumns: [] });
    },
    removeJoinTable(index) { this.config.joins.splice(index, 1); this.syncOutputColumns(); },
    async onJoinDbChange(join) {
      const id = join.dbId; join.tableName = ''; join.columns = []; join.availableTables = []; join.availableColumns = []; join.targetKey = '';
      this.syncOutputColumns(); if (!id) return;
      try { const tables = await this.metadata(this.sourceUrl(join)); if (id === join.dbId) join.availableTables = tables; }
      catch (error) { this.showError('Không đọc được bảng phụ', error, () => this.onJoinDbChange(join)); }
    },
    async onJoinTableChange(join) {
      const key = `${join.dbId}/${join.tableName}`; join.columns = []; join.availableColumns = []; join.targetKey = ''; this.syncOutputColumns();
      if (!join.dbId || !join.tableName) return;
      try {
        const columns = await this.metadata(`${this.sourceUrl(join)}/${encodeURIComponent(join.tableName)}/columns`);
        if (`${join.dbId}/${join.tableName}` !== key) return;
        join.availableColumns = columns; join.columns = columns.slice(0, 200).map(column => column.name);
        join.targetKey = columns.find(column => column.name.toLowerCase() === 'id')?.name || '';
        this.syncOutputColumns();
      } catch (error) { this.showError('Không đọc được cột phụ', error, () => this.onJoinTableChange(join)); }
    },
    sourceKeys(index) {
      const base = this.config.baseTable;
      const fields = this.baseColumns.map(col => `${base.alias || base.tableName}.${col.name}`);
      this.config.joins.slice(0, index).forEach(join => (join.availableColumns || []).forEach(col => fields.push(`${join.alias || join.tableName}.${col.name}`)));
      return fields;
    },
    selectAllBase(selected) { this.config.baseTable.columns = selected ? this.baseColumns.slice(0, 200).map(col => col.name) : []; this.syncOutputColumns(); },
    syncOutputColumns() {
      if (this.config.outputMode !== 'TABLE') return;
      const available = new Set(this.allSelectedFields);
      this.config.outputColumns = this.config.outputColumns.filter(column => available.has(column.field));
      for (const field of available) {
        if (this.config.outputColumns.length >= 200) break;
        if (!this.config.outputColumns.some(column => column.field === field)) this.config.outputColumns.push({ field, title: field.split('.').at(-1), type: 'AUTO', format: 'GENERAL' });
      }
    },
    addOutputColumn() { this.config.outputColumns.push({ field: this.allSelectedFields[0] || '', title: 'Cột mới', type: 'AUTO', format: 'GENERAL' }); },
    moveColumn(from, to) {
      if (this.isTemplate || from === null || from === to || to < 0 || to >= this.config.outputColumns.length) return;
      const [column] = this.config.outputColumns.splice(from, 1); this.config.outputColumns.splice(to, 0, column); this.draggedColumn = null;
    },
    async changeOutputMode() {
      this.config.outputColumns = [];
      if (this.isTemplate && this.config.templateId) { try { await this.refreshHeaders(); } catch (error) { this.showError('Không đọc được template', error); } }
      else this.syncOutputColumns();
    },
    async handleFileUpload(event) {
      const file = event.target.files[0]; if (!file) return;
      if (!file.name.toLowerCase().endsWith('.xlsx') || file.size > 50 * 1024 * 1024) { this.errors.template = 'Chọn file .xlsx không quá 50 MB.'; return; }
      this.uploadLoading = true; this.errors.template = '';
      const form = new FormData(); form.append('file', file);
      try {
        const result = await this.api('/api/templates', { method: 'POST', body: form });
        this.config.templateId = result.templateId; this.templateFileName = file.name; this.excelColumns = result.columns;
        if (typeof result.headerRow === 'number') this.config.headerRow = result.headerRow;
        this.templateColumns(); this.toast(`Đã đọc cấu trúc template (dòng tiêu đề: ${this.config.headerRow + 1}).`);
      } catch (error) { this.showError('Không tải được template', error); }
      finally { this.uploadLoading = false; event.target.value = ''; }
    },
    async autoDetectHeader() {
      if (!this.config.templateId) return;
      this.uploadLoading = true;
      try {
        const detected = await this.api(`/api/templates/${encodeURIComponent(this.config.templateId)}/detect-header`);
        if (typeof detected === 'number') {
          this.config.headerRow = detected;
          await this.refreshHeaders();
          this.toast(`Đã phát hiện dòng tiêu đề: ${this.config.headerRow + 1}`);
        }
      } catch (error) { this.showError('Không tự động phát hiện được dòng tiêu đề', error); }
      finally { this.uploadLoading = false; }
    },
    templateColumns() {
      const old = this.config.outputColumns;
      this.config.outputColumns = this.excelColumns.map(header => {
        const existing = old.find(column => column.letter === header.letter);
        return { letter: header.letter, title: header.title, field: existing?.field || '', type: existing?.type || 'AUTO', format: existing?.format || 'GENERAL' };
      });
      this.smartAutoMatch();
    },
    smartAutoMatch() {
      const normalize = value => value.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd').replace(/[^a-z0-9]/g, '');
      this.config.outputColumns.forEach(column => {
        if (column.field) return;
        const exact = this.allSelectedFields.filter(field => normalize(field.split('.').at(-1)) === normalize(column.title));
        if (exact.length === 1) column.field = exact[0];
      });
    },
    async refreshHeaders() {
      this.excelColumns = await this.api(`/api/templates/${encodeURIComponent(this.config.templateId)}/columns?headerRow=${this.config.headerRow}`);
      this.templateColumns();
    },
    async onHeaderRowChange() { if (this.config.templateId) try { await this.refreshHeaders(); } catch (error) { this.showError('Không đọc được tiêu đề', error); } },
    addFilter() { this.config.filters.push({ field: this.allSelectedFields[0] || '', operator: 'CONTAINS', value: '' }); },
    validateConfig() {
      this.errors = {}; const base = this.config.baseTable;
      if (!base.dbId || !base.tableName || !base.columns.length) this.errors.source = 'Chọn database, bảng gốc và ít nhất một cột nguồn.';
      const names = new Set([base.alias || base.tableName]);
      for (const join of this.config.joins) {
        const name = join.alias || join.tableName;
        if (!join.dbId || !join.tableName || !join.sourceKey || !join.targetKey || names.has(name)) this.errors.source = 'Hoàn thiện bảng phụ, khóa ghép và dùng alias khác nhau cho mỗi nguồn.';
        names.add(name);
      }
      if (this.isTemplate && !this.config.templateId) this.errors.template = 'Tải template trước khi xem hoặc xuất.';
      const columns = this.config.outputColumns.filter(column => column.field);
      if (!columns.length || columns.length > 200 || columns.some(column => !column.title.trim() || !this.allSelectedFields.includes(column.field))) this.errors.output = 'Chọn từ 1 đến 200 cột đầu ra có tên và trường nguồn hợp lệ.';
      if ([base.filterColumn, base.fromDate, base.toDate].some(Boolean) && ![base.filterColumn, base.fromDate, base.toDate].every(Boolean)) this.errors.filter = 'Bộ lọc ngày cần đủ cột, ngày bắt đầu và ngày kết thúc.';
      if (base.fromDate && base.toDate && base.fromDate > base.toDate) this.errors.filter = 'Ngày kết thúc phải từ ngày bắt đầu trở đi.';
      if (this.config.filters.some(filter => !this.allSelectedFields.includes(filter.field) || !['EMPTY', 'NOT_EMPTY'].includes(filter.operator) && !filter.value.trim())) this.errors.filter = 'Hoàn thiện trường, phép so sánh và giá trị của bộ lọc.';
      if (this.errors.source) this.step = 1; else if (this.errors.template || this.errors.output) this.step = 2; else if (this.errors.filter) this.step = 3;
      return !Object.keys(this.errors).length;
    },
    async fetchPreview() {
      if (!this.validateConfig()) return;
      this._previewController?.abort(); const controller = new AbortController(); this._previewController = controller;
      const id = ++this.previewRequestId; const config = this.requestConfig();
      config.outputColumns = config.outputColumns.filter(column => column.field);
      const signature = this.currentSignature; this._previewSignature = signature;
      this.previewLoading = true; this.step = 4;
      try {
        const preview = await this.post('/api/reports/preview-details', config, { signal: controller.signal });
        if (id !== this.previewRequestId || signature !== this.currentSignature) return;
        this.preview = preview; this.previewSignature = signature;
      } catch (error) { if (error.name !== 'AbortError' && id === this.previewRequestId) this.showError('Không tạo được preview', error, () => this.fetchPreview()); }
      finally { if (id === this.previewRequestId) { this.previewLoading = false; this._previewController = null; } }
    },
    stopPreview() { this._previewController?.abort(); this._previewController = null; this.previewRequestId++; this.previewLoading = false; },
    async exportReport() {
      if (!this.validateConfig()) return;
      if (!this.canExport) { this.toast('Cập nhật preview trước khi tạo file.', 'info'); this.step = 4; return; }
      this.exportLoading = true;
      try {
        const config = this.requestConfig(); config.outputColumns = config.outputColumns.filter(column => column.field);
        this.jobSignature = this.currentSignature;
        this.job = await this.post('/api/reports/jobs', config);
        try { localStorage.setItem('report_active_job', this.job.id); } catch (_) { }
        this.toast('Đã gửi tác vụ xuất. Bạn có thể tiếp tục thao tác.'); this.pollJob(this.job.id);
      } catch (error) { this.showError('Không tạo được tác vụ', error, () => this.exportReport()); }
      finally { this.exportLoading = false; }
    },
    async pollJob(id) {
      const sequence = ++this.jobPollSequence;
      clearTimeout(this._jobTimer);
      try {
        const job = await this.api(`/api/reports/jobs/${encodeURIComponent(id)}`);
        if (sequence !== this.jobPollSequence) return;
        this.job = job; this.jobPollingError = '';
        if (['READY', 'FAILED', 'CANCELLED'].includes(this.job.state)) {
          try { localStorage.removeItem('report_active_job'); } catch (_) { }
          if (this.job.state === 'READY') this.toast('File Excel đã sẵn sàng.');
          if (this.job.state === 'FAILED') this.showError('Xuất báo cáo thất bại', new Error(this.job.message));
        } else this._jobTimer = setTimeout(() => this.pollJob(id), 1500);
      } catch (error) { if (sequence === this.jobPollSequence) this.jobPollingError = error.message; }
    },
    async cancelJob() {
      if (!this.job || !await this.ask('Hủy tác vụ xuất?', 'File đang tạo sẽ bị bỏ. Các thiết lập báo cáo vẫn được giữ.', { confirm: 'Hủy tác vụ', destructive: true })) return;
      try { this.job = await this.api(`/api/reports/jobs/${encodeURIComponent(this.job.id)}`, { method: 'DELETE' }); this.jobPollSequence++; clearTimeout(this._jobTimer); localStorage.removeItem('report_active_job'); }
      catch (error) { this.showError('Không hủy được tác vụ', error); }
    },
    async downloadJob() {
      if (!this.job || this.job.state !== 'READY') return;
      if (this.job.warnings.length && !await this.ask('Kiểm tra cảnh báo trước khi tải', this.job.warnings.join('\n'), { confirm: 'Vẫn tải file' })) return;
      const link = document.createElement('a'); link.href = `/api/reports/jobs/${encodeURIComponent(this.job.id)}/download?acknowledged=true`;
      link.download = ''; document.body.appendChild(link); link.click(); link.remove();
    },
    openNewDatabase() {
      this.newDb = { name: '', dbType: this.databaseTypes[0]?.id || 'MYSQL', host: 'localhost', port: 3306, database: '', schema: '', username: '', password: '' };
      this.onDbTypeChange(); this.fieldErrors = {}; this._previousFocus = document.activeElement; this.showConnectModal = true; this.focusModal();
    },
    editDatabase(db) {
      this.newDb = { id: db.id, name: db.name, dbType: db.dbType === 'MSSQL' ? 'SQLSERVER' : db.dbType, host: db.host, port: db.port,
        database: db.databaseName, schema: db.schema || '', username: db.username || '', password: '' };
      this.fieldErrors = {}; this._previousFocus = document.activeElement; this.showConnectModal = true; this.focusModal();
    },
    closeConnection() { if (this.connectLoading) return; this.newDb.password = ''; this.showConnectModal = false; this.$nextTick(() => this._previousFocus?.focus()); },
    onDbTypeChange() { const type = this.databaseTypes.find(type => type.id === this.newDb.dbType); if (type) this.newDb.port = type.defaultPort; this.newDb.schema = ''; },
    async saveNewDatabase() {
      this.fieldErrors = {};
      for (const [key, label] of [['name', 'tên gợi nhớ'], ['host', 'host'], ['database', 'database / service name'], ['username', 'tài khoản']]) if (!String(this.newDb[key] || '').trim()) this.fieldErrors[key] = `Nhập ${label}.`;
      if (!Number.isInteger(this.newDb.port) || this.newDb.port < 1 || this.newDb.port > 65535) this.fieldErrors.port = 'Cổng từ 1 đến 65535.';
      if (Object.keys(this.fieldErrors).length) return;
      this.connectLoading = true;
      try {
        await this.post('/api/databases', this.newDb); this.newDb.password = ''; this.showConnectModal = false;
        this._metadataCache?.clear(); await this.fetchDatabases(); this.toast('Đã kết nối database.');
        this.$nextTick(() => this._previousFocus?.focus());
      } catch (error) { this.fieldErrors.connection = error.message; }
      finally { this.connectLoading = false; }
    },
    async deleteDatabase(id) {
      if (!await this.ask('Xóa kết nối?', 'Các báo cáo dùng kết nối này sẽ cần chọn nguồn khác. Dữ liệu trong database không bị xóa.', { confirm: 'Xóa kết nối', destructive: true })) return;
      try {
        await this.api(`/api/databases/${encodeURIComponent(id)}`, { method: 'DELETE' }); this._metadataCache?.clear(); await this.fetchDatabases();
        if (this.config.baseTable.dbId === id) { this.config.baseTable.dbId = ''; await this.onBaseDbChange(); }
        this.toast('Đã xóa kết nối.');
      } catch (error) { this.showError('Không xóa được kết nối', error); }
    },
    loadSavedProfilesFromStorage() {
      try { this.savedProfiles = JSON.parse(localStorage.getItem('saved_report_profiles') || '{}'); }
      catch (_) { this.savedProfiles = {}; this.toast('Không đọc được mẫu đã lưu trong trình duyệt.', 'info'); }
    },
    async saveCurrentProfile() {
      const name = await this.ask('Lưu mẫu báo cáo', 'Mẫu lưu cấu hình nguồn, cột và bộ lọc trên trình duyệt này.', { input: true, value: this.selectedProfile, confirm: 'Lưu mẫu' });
      if (!name) return;
      if (Object.prototype.hasOwnProperty.call(this.savedProfiles, name) && !await this.ask('Ghi đè mẫu?', `Mẫu “${name}” đã có. Bạn muốn thay bằng cấu hình hiện tại?`, { confirm: 'Ghi đè' })) return;
      try {
        this.savedProfiles = { ...this.savedProfiles, [name]: this.requestConfig() };
        localStorage.setItem('saved_report_profiles', JSON.stringify(this.savedProfiles)); this.selectedProfile = name; this.toast(`Đã lưu mẫu “${name}”.`);
      } catch (error) { this.showError('Không lưu được mẫu', error); }
    },
    async loadProfile() {
      const saved = this.savedProfiles[this.selectedProfile]; if (!saved) return;
      const config = { ...emptyConfig(), ...copy(saved) };
      if (!saved.outputMode) config.outputMode = saved.templateId ? 'TEMPLATE' : 'TABLE';
      this.config = config; this.preview = null; this.excelColumns = []; this.step = 1;
      try {
        const load = async source => ({ tables: await this.metadata(this.sourceUrl(source)),
          columns: await this.metadata(`${this.sourceUrl(source)}/${encodeURIComponent(source.tableName)}/columns`) });
        const base = await load(config.baseTable); this.baseTables = base.tables; this.baseColumns = base.columns;
        await Promise.all(config.joins.map(async join => { const data = await load(join); join.availableTables = data.tables; join.availableColumns = data.columns; }));
        if (config.outputMode === 'TEMPLATE') {
          if (!config.outputColumns.length) config.outputColumns = Object.entries(config.mapping || {}).filter(([, field]) => field).map(([letter, field]) => ({ letter, field, type: 'AUTO', format: 'GENERAL' }));
          this.templateFileName = config.templateId; await this.refreshHeaders();
        } else if (!config.outputColumns.length) this.syncOutputColumns();
        this.toast(`Đã nạp mẫu “${this.selectedProfile}”.`);
      } catch (error) { this.showError('Chưa nạp đủ cấu hình', error, () => this.loadProfile()); }
    }
  }
}).mount('#app');

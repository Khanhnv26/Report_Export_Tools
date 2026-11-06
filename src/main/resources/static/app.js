const { createApp } = Vue;

createApp({
  data() {
    return {
      isDark: true,
      activeTab: 'map',
      showConnectModal: false,
      connectLoading: false,
      previewLoading: false,
      exportLoading: false,

      databases: [],
      databaseTypes: [],

      newDb: {
        name: '',
        dbType: 'MYSQL',
        host: 'localhost',
        port: 3306,
        database: '',
        schema: '',
        username: 'root',
        password: ''
      },

      templateId: '',
      templateFileName: '',
      excelColumns: [],

      baseTables: [],
      baseColumns: [],

      config: {
        templateId: '',
        headerRow: 0,
        baseTable: {
          dbId: '',
          tableName: '',
          alias: '',
          columns: [],
          filterColumn: '',
          fromDate: '',
          toDate: ''
        },
        joins: [],
        mapping: {}
      },

      previewRows: [],

      savedProfiles: {},
      selectedProfile: ''
    };
  },

  computed: {
    selectedDatabaseType() {
      return this.databaseTypes.find(type => type.id === this.newDb.dbType)
        || { databaseLabel: 'Database', supportsSchema: false };
    },
    allSelectedFields() {
      const list = [];
      const baseName = this.config.baseTable.alias || this.config.baseTable.tableName;
      if (baseName && this.config.baseTable.columns) {
        this.config.baseTable.columns.forEach(col => {
          list.push(`${baseName}.${col}`);
        });
      }

      this.config.joins.forEach(j => {
        if (j.tableName && j.columns) {
          j.columns.forEach(col => {
            list.push(`${j.alias || j.tableName}.${col}`);
          });
        }
      });
      return list;
    }
  },

  mounted() {
    this.initTheme();
    this.loadSavedProfilesFromStorage();
    this.fetchDatabaseTypes();
    this.fetchDatabases();
  },

  methods: {
    async fetchDatabaseTypes() {
      try {
        const res = await fetch('/api/database-types');
        const json = await res.json();
        if (!res.ok || !Array.isArray(json.data)) throw new Error(json.message || 'Không đọc được loại database');
        this.databaseTypes = json.data;
      } catch (err) {
        alert('Không tải được danh sách database hỗ trợ: ' + err.message);
      }
    },
    initTheme() {
      const saved = localStorage.getItem('theme');
      if (saved) {
        this.isDark = saved === 'dark';
      } else {
        this.isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
      }
      this.applyTheme();
    },

    toggleTheme() {
      this.isDark = !this.isDark;
      localStorage.setItem('theme', this.isDark ? 'dark' : 'light');
      this.applyTheme();
    },

    applyTheme() {
      if (this.isDark) {
        document.documentElement.classList.add('dark');
      } else {
        document.documentElement.classList.remove('dark');
      }
    },

    async fetchDatabases() {
      try {
        const res = await fetch('/api/databases');
        const json = await res.json();
        if (json.status === 200 && json.data) {
          this.databases = json.data;
          if (!this.config.baseTable.dbId && this.databases.length > 0) {
            this.config.baseTable.dbId = (this.databases.find(db => db.connected) || this.databases[0]).id;
            this.onBaseDbChange();
          } else if (this.config.baseTable.dbId && !this.config.baseTable.tableName
              && this.databases.some(db => db.id === this.config.baseTable.dbId && db.connected)) {
            this.onBaseDbChange();
          }
        }
      } catch (err) {
        console.error('Lỗi khi tải danh sách database:', err);
      }
    },

    onDbTypeChange() {
      const type = this.databaseTypes.find(type => type.id === this.newDb.dbType);
      if (type) this.newDb.port = type.defaultPort;
      this.newDb.schema = '';
    },

    editDatabase(db) {
      this.newDb = {
        id: db.id, name: db.name, dbType: db.dbType === 'MSSQL' ? 'SQLSERVER' : db.dbType, host: db.host, port: db.port,
        database: db.databaseName, schema: db.schema || '', username: db.username || '', password: ''
      };
      this.showConnectModal = true;
    },

    openNewDatabase() {
      this.newDb = { name: '', dbType: 'MYSQL', host: 'localhost', port: 3306,
        database: '', schema: '', username: '', password: '' };
      this.onDbTypeChange();
      this.showConnectModal = true;
    },

    async saveNewDatabase() {
      if (!this.newDb.host || !this.newDb.database) {
        alert('Vui lòng nhập Host và Tên Database!');
        return;
      }

      this.connectLoading = true;
      try {
        const res = await fetch('/api/databases', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(this.newDb)
        });

        const json = await res.json();
        if (res.ok && json.status === 201) {
          alert('Kết nối Database thành công!');
          this.showConnectModal = false;
          this.newDb.name = '';
          this.newDb.database = '';
          this.newDb.password = '';
          await this.fetchDatabases();
        } else {
          alert('Kết nối thất bại: ' + (json.message || 'Kiểm tra lại thông số'));
        }
      } catch (err) {
        alert('Lỗi khi gọi API kết nối: ' + err.message);
      } finally {
        this.connectLoading = false;
      }
    },

    async deleteDatabase(dbId) {
      if (!confirm('Bạn có chắc muốn xóa kết nối Database này?')) return;
      try {
        const res = await fetch(`/api/databases/${dbId}`, { method: 'DELETE' });
        if (res.ok) {
          await this.fetchDatabases();
          if (this.config.baseTable.dbId === dbId) {
            this.config.baseTable.dbId = '';
            this.baseTables = [];
            this.baseColumns = [];
          }
        }
      } catch (err) {
        alert('Lỗi xóa Database: ' + err.message);
      }
    },

    async onBaseDbChange() {
      const dbId = this.config.baseTable.dbId;
      this.baseTables = [];
      this.config.baseTable.tableName = '';
      this.baseColumns = [];
      this.config.baseTable.columns = [];

      if (!dbId) return;
      try {
        const res = await fetch(`/api/databases/${dbId}/tables`);
        const json = await res.json();
        if (json.status === 200 && json.data) {
          this.baseTables = json.data;
          if (this.baseTables.length > 0) {
            this.config.baseTable.tableName = this.baseTables[0];
            this.onBaseTableChange();
          }
        }
      } catch (err) {
        console.error('Lỗi tải danh sách bảng gốc:', err);
      }
    },

    async onBaseTableChange() {
      const dbId = this.config.baseTable.dbId;
      const table = this.config.baseTable.tableName;
      this.baseColumns = [];
      this.config.baseTable.columns = [];

      if (!dbId || !table) return;
      try {
        const res = await fetch(`/api/databases/${encodeURIComponent(dbId)}/tables/${encodeURIComponent(table)}/columns`);
        const json = await res.json();
        if (json.status === 200 && json.data) {
          this.baseColumns = json.data;
          this.config.baseTable.columns = this.baseColumns.map(c => c.name);
        }
      } catch (err) {
        console.error('Lỗi tải danh sách cột bảng gốc:', err);
      }
    },

    addJoinTable() {
      const defaultDbId = this.databases.length > 1 ? this.databases[1].id : (this.databases[0]?.id || '');
      const newJoin = {
        dbId: defaultDbId,
        tableName: '',
        alias: `join_${Date.now()}_${this.config.joins.length + 1}`,
        columns: [],
        sourceKey: '',
        targetKey: 'id',
        availableTables: [],
        availableColumns: []
      };
      this.config.joins.push(newJoin);
      if (defaultDbId) {
        this.onJoinDbChange(newJoin);
      }
    },

    removeJoinTable(idx) {
      this.config.joins.splice(idx, 1);
    },

    async onJoinDbChange(join) {
      join.availableTables = [];
      join.tableName = '';
      join.availableColumns = [];
      join.columns = [];

      if (!join.dbId) return;
      try {
        const res = await fetch(`/api/databases/${join.dbId}/tables`);
        const json = await res.json();
        if (json.status === 200 && json.data) {
          join.availableTables = json.data;
          if (join.availableTables.length > 0) {
            join.tableName = join.availableTables[0];
            this.onJoinTableChange(join);
          }
        }
      } catch (err) {
        console.error('Lỗi tải danh sách bảng phụ:', err);
      }
    },

    async onJoinTableChange(join) {
      join.availableColumns = [];
      join.columns = [];

      if (!join.dbId || !join.tableName) return;
      try {
        const res = await fetch(`/api/databases/${encodeURIComponent(join.dbId)}/tables/${encodeURIComponent(join.tableName)}/columns`);
        const json = await res.json();
        if (json.status === 200 && json.data) {
          join.availableColumns = json.data;
          join.columns = join.availableColumns.map(c => c.name);
        }
      } catch (err) {
        console.error('Lỗi tải danh sách cột bảng phụ:', err);
      }
    },

    async handleFileUpload(event) {
      const file = event.target.files[0];
      if (!file) return;

      this.templateFileName = file.name;
      const formData = new FormData();
      formData.append('file', file);
      formData.append('headerRow', this.config.headerRow || 0);

      try {
        const res = await fetch('/api/templates', {
          method: 'POST',
          body: formData
        });
        const json = await res.json();
        if (res.ok && json.status === 201 && json.data) {
          this.templateId = json.data.templateId;
          this.config.templateId = this.templateId;
          this.excelColumns = json.data.columns;

          this.config.mapping = Object.fromEntries(this.excelColumns.map(c => [c.letter, '']));

          this.smartAutoMatch();
        } else {
          alert('Lỗi đọc file Excel: ' + (json.message || 'Kiểm tra định dạng file'));
        }
      } catch (err) {
        alert('Lỗi tải file template: ' + err.message);
      }
    },

    smartAutoMatch() {
      if (!this.excelColumns.length || !this.allSelectedFields.length) return;

      const normalize = (str) => {
        return str.toLowerCase()
          .normalize("NFD").replace(/[\u0300-\u036f]/g, "")
          .replace(/[^a-z0-9]/g, "");
      };

      this.excelColumns.forEach(col => {
        if (this.config.mapping[col.letter]) return;
        const targetClean = normalize(col.title);
        for (const field of this.allSelectedFields) {
          const colName = field.includes('.') ? field.split('.')[1] : field;
          const fieldClean = normalize(colName);

          if (fieldClean === targetClean || targetClean.includes(fieldClean) || fieldClean.includes(targetClean)) {
            this.config.mapping[col.letter] = field;
            break;
          }

          const isId = fieldClean === 'id' || fieldClean.endsWith('id');
          const isCode = !isId && targetClean.includes('ma') && fieldClean.includes('code');
          const isName = !isId && (targetClean.includes('ten') || targetClean.includes('khach')) && (fieldClean.includes('name') || (fieldClean.includes('user') && !isId));
          const isPhone = !isId && (targetClean.includes('sdt') || targetClean.includes('thoai')) && fieldClean.includes('phone');
          const isMoney = !isId && (targetClean.includes('tien') || targetClean.includes('tong') || targetClean.includes('gia')) && (fieldClean.includes('amount') || fieldClean.includes('price') || fieldClean.includes('total'));
          const isDate = !isId && (targetClean.includes('ngay') || targetClean.includes('gio') || targetClean.includes('gian')) && (fieldClean.includes('time') || fieldClean.includes('at') || fieldClean.includes('date'));
          const isStatus = !isId && targetClean.includes('trangthai') && fieldClean.includes('status');

          if (isCode || isName || isPhone || isMoney || isDate || isStatus) {
            this.config.mapping[col.letter] = field;
            break;
          }
        }
      });
    },

    async fetchPreview() {
      if (!this.config.baseTable.dbId || !this.config.baseTable.tableName) {
        alert('Vui lòng chọn Database và Bảng gốc!');
        return;
      }

      this.activeTab = 'preview';
      this.previewLoading = true;
      try {
        const res = await fetch('/api/reports/preview', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(this.config)
        });

        const json = await res.json();
        if (res.ok && json.status === 200 && json.data) {
          this.previewRows = json.data;
        } else {
          alert('Lỗi xem trước: ' + (json.message || 'Kiểm tra lại cấu hình'));
        }
      } catch (err) {
        alert('Lỗi khi gọi API preview: ' + err.message);
      } finally {
        this.previewLoading = false;
      }
    },

    async exportReport() {
      if (!this.config.templateId) {
        alert('Vui lòng tải lên file Excel mẫu ở Mục 2 trước khi xuất!');
        return;
      }
      if (!this.config.baseTable.dbId || !this.config.baseTable.tableName) {
        alert('Vui lòng chọn Bảng gốc ở Mục 3!');
        return;
      }

      this.exportLoading = true;
      try {
        const res = await fetch('/api/reports/export', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(this.config)
        });

        if (!res.ok) {
          const json = await res.json();
          alert('Lỗi xuất file: ' + (json.message || 'Lỗi không xác định'));
          return;
        }

        const blob = await res.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `Bao_Cao_${new Date().toISOString().slice(0, 10)}.xlsx`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
      } catch (err) {
        alert('Lỗi xuất file Excel: ' + err.message);
      } finally {
        this.exportLoading = false;
      }
    },

    formatCellValue(val) {
      if (val === null || val === undefined) return '-';
      if (typeof val === 'number') {
        return val.toLocaleString('vi-VN');
      }
      return String(val);
    },

    loadSavedProfilesFromStorage() {
      try {
        const stored = localStorage.getItem('saved_report_profiles');
        if (stored) {
          this.savedProfiles = JSON.parse(stored);
        }
      } catch (err) {
        console.warn('Lỗi đọc profiles từ storage:', err);
      }
    },

    saveCurrentProfile() {
      const name = prompt('Nhập tên gợi nhớ cho mẫu cấu hình này (VD: Báo cáo Doanh thu tuần):');
      if (!name || !name.trim()) return;

      this.savedProfiles[name.trim()] = JSON.parse(JSON.stringify(this.config));
      localStorage.setItem('saved_report_profiles', JSON.stringify(this.savedProfiles));
      this.selectedProfile = name.trim();
      alert(`Đã lưu mẫu "${name}" thành công!`);
    },

    async refreshHeaders() {
      if (!this.config.templateId) return;
      const res = await fetch(`/api/templates/${encodeURIComponent(this.config.templateId)}/columns?headerRow=${this.config.headerRow}`);
      const json = await res.json();
      if (!res.ok) throw new Error(json.message || 'Không đọc được template');
      this.excelColumns = json.data;
      this.config.mapping = Object.fromEntries(this.excelColumns.map(c => [c.letter, this.config.mapping[c.letter] || '']));
    },

    async onHeaderRowChange() {
      try { await this.refreshHeaders(); }
      catch (err) { alert(err.message); }
    },

    async loadProfile() {
      if (!this.selectedProfile || !this.savedProfiles[this.selectedProfile]) return;
      const profileData = this.savedProfiles[this.selectedProfile];
      this.config = JSON.parse(JSON.stringify(profileData));
      this.templateId = this.config.templateId;
      this.templateFileName = this.templateId;
      this.previewRows = [];
      this.excelColumns = [];
      try {
        const loadMetadata = async (source) => {
          const base = `/api/databases/${encodeURIComponent(source.dbId)}/tables`;
          const [tables, columns] = await Promise.all([
            fetch(base), fetch(`${base}/${encodeURIComponent(source.tableName)}/columns`)
          ]);
          const [tableData, columnData] = await Promise.all([tables.json(), columns.json()]);
          if (!tables.ok || !columns.ok) throw new Error(tableData.message || columnData.message || 'Không đọc được cấu hình nguồn');
          return { tables: tableData.data, columns: columnData.data };
        };
        const metadata = await loadMetadata(this.config.baseTable);
        this.baseTables = metadata.tables;
        this.baseColumns = metadata.columns;
        await Promise.all(this.config.joins.map(async join => {
          const metadata = await loadMetadata(join);
          join.availableTables = metadata.tables;
          join.availableColumns = metadata.columns;
        }));
        await this.refreshHeaders();
        alert(`Đã nạp mẫu cấu hình "${this.selectedProfile}"!`);
      } catch (err) {
        alert('Không thể nạp đầy đủ cấu hình: ' + err.message);
      }
    }
  }
}).mount('#app');

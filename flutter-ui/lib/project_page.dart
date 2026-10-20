part of 'main.dart';

class ProjectPage extends StatefulWidget {
  final String path;
  const ProjectPage({Key? key, required this.path}) : super(key: key);
  @override
  State<ProjectPage> createState() => _ProjectPageState();
}

class _ProjectPageState extends State<ProjectPage> with WidgetsBindingObserver {
  static const signingFields = {'signingPassword', 'signingAliasPassword'};
  static const allAbis = ['arm64-v8a', 'armeabi-v7a'];
  static const abiChoices = <String, List<String>>{
    'all': allAbis,
    'arm64-v8a': ['arm64-v8a'],
    'armeabi-v7a': ['armeabi-v7a']
  };
  static const abiLabels = <String, String>{
    'all': '全部架构（不限制，由打包模板决定）',
    'arm64-v8a': '仅 arm64-v8a（64 位）',
    'armeabi-v7a': '仅 armeabi-v7a（32 位）'
  };
  // Common permissions taken from the original application manifest.
  static const permissionOptions = <String, String>{
    'android.permission.INTERNET': '网络访问（INTERNET）',
    'android.permission.ACCESS_NETWORK_STATE': '网络状态（ACCESS_NETWORK_STATE）',
    'android.permission.ACCESS_WIFI_STATE': 'Wi-Fi 状态（ACCESS_WIFI_STATE）',
    'android.permission.READ_EXTERNAL_STORAGE': '读取外部存储（READ_EXTERNAL_STORAGE）',
    'android.permission.WRITE_EXTERNAL_STORAGE':
        '写入外部存储（WRITE_EXTERNAL_STORAGE）',
    'android.permission.MANAGE_EXTERNAL_STORAGE':
        '管理全部文件（MANAGE_EXTERNAL_STORAGE）',
    'android.permission.SYSTEM_ALERT_WINDOW': '悬浮窗（SYSTEM_ALERT_WINDOW）',
    'android.permission.FOREGROUND_SERVICE': '前台服务（FOREGROUND_SERVICE）',
    'android.permission.RECEIVE_BOOT_COMPLETED': '开机自启（RECEIVE_BOOT_COMPLETED）',
    'android.permission.WAKE_LOCK': '保持唤醒（WAKE_LOCK）',
    'android.permission.VIBRATE': '振动（VIBRATE）',
    'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS':
        '忽略电池优化（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）',
    'android.permission.CAMERA': '相机（CAMERA）',
    'android.permission.RECORD_AUDIO': '录音（RECORD_AUDIO）',
    'android.permission.ACCESS_FINE_LOCATION': '精确位置（ACCESS_FINE_LOCATION）',
    'android.permission.READ_PHONE_STATE': '读取电话状态（READ_PHONE_STATE）',
    'android.permission.QUERY_ALL_PACKAGES': '查看已安装应用（QUERY_ALL_PACKAGES）',
    'android.permission.POST_NOTIFICATIONS': '发送通知（POST_NOTIFICATIONS）'
  };
  final form = GlobalKey<FormState>();
  final fields = <String, TextEditingController>{
    for (final name in [
      'name',
      'packageName',
      'versionName',
      'versionCode',
      'main',
      'icon',
      'splashText',
      'assets',
      'useFeatures',
      'signingAlias',
      'signingPassword',
      'signingAliasPassword'
    ])
      name: TextEditingController()
  };
  Map<String, dynamic>? loaded, job;
  Map<String, dynamic> baseline = {};
  String? error, statusError;
  int encryptLevel = 0, loadToken = 0, jobGeneration = 0;
  bool displaySplash = true, hideLogs = false, stableMode = false;
  bool loading = false, saving = false, submitting = false, actionBusy = false;
  bool statusBusy = false, leaving = false, appActive = true;
  List<String> abis = List<String>.from(allAbis);
  Set<String> permissions = <String>{};
  Set<String> startupPermissions = <String>{};
  Map<String, dynamic> features = {}, plugins = {}, selectedSigning = {};
  Map<String, bool> optimization = {
    'unusedResources': false,
    'removeOpenCv': false,
    'removeAccessibilityService': false,
    'obfuscateComponents': false
  };
  bool continuation = false;
  Map<String, dynamic> signing = {};
  Map<String, dynamic> packagedAbis = {};
  bool clearSigning = false;
  Map<String, dynamic>? nativeBridge;
  Timer? timer;
  bool get busy => loading || saving || submitting || actionBusy;
  bool get running => job?['status'] == 'running';
  String get projectPath => loaded?['path']?.toString() ?? widget.path;
  String get revision => loaded?['revision']?.toString() ?? '';
  bool get dirty =>
      loaded != null &&
      (jsonEncode(values()) != jsonEncode(baseline) || signingPatch() != null);

  /// null when the stored list is not one of the supported combinations.
  String? get abiChoice {
    final current = List<String>.from(abis)..sort();
    for (final entry in abiChoices.entries) {
      final choice = List<String>.from(entry.value)..sort();
      if (current.join(',') == choice.join(',')) return entry.key;
    }
    return null;
  }

  bool get customSignature =>
      (selectedSigning['keystore']?.toString() ?? '').isNotEmpty;

  bool get hasSavedSigningPassword => signing['hasPassword'] == true;

  List<String> abiInfo(String name) => (packagedAbis[name] as List? ?? [])
      .map((value) => value.toString())
      .toList();

  String get abiHelper {
    final parts = <String>[];
    if (abiInfo('shipped').isNotEmpty)
      parts.add('本机运行库包含 ${abiInfo('shipped').join('、')}');
    if (abiInfo('template').isNotEmpty)
      parts.add('打包模板保留 ${abiInfo('template').join('、')}');
    return parts.isEmpty ? '按所选架构保留打包产物中的本地库' : parts.join('；');
  }

  List<String> get unavailableAbis {
    final shipped = abiInfo('shipped');
    if (shipped.isEmpty) return const [];
    return abis.where((abi) => !shipped.contains(abi)).toList();
  }

  List<String> get extraPermissions => (permissions
      .where((name) => !permissionOptions.containsKey(name))
      .toList()
    ..sort());

  Map<String, dynamic> values() => {
        'name': fields['name']!.text,
        'packageName': fields['packageName']!.text,
        'versionName': fields['versionName']!.text,
        'versionCode': fields['versionCode']!.text,
        'abis': List<String>.from(abis),
        'main': fields['main']!.text,
        'encryptLevel': encryptLevel,
        'icon': fields['icon']!.text,
        'assets': lines(fields['assets']!.text),
        'useFeatures': [
          if (continuation) 'continuation',
          ...lines(fields['useFeatures']!.text)
              .where((item) => item != 'continuation')
        ],
        'features': features,
        'plugins': plugins,
        'optimization': optimization,
        'signingConfig': {
          ...selectedSigning,
          'alias': fields['signingAlias']!.text
        },
        'permissionConfig': {
          'manifestPermissions': permissions.toList()..sort(),
          'requestListOnStartup': startupPermissions.toList()..sort()
        },
        'launchConfig': {
          'displaySplash': displaySplash,
          'hideLogs': hideLogs,
          'stableMode': stableMode,
          'splashText': fields['splashText']!.text
        }
      };

  List<String> lines(String value) => value
      .split(RegExp(r'[\r\n]+'))
      .map((line) => line.trim())
      .where((line) => line.isNotEmpty)
      .toSet()
      .toList();

  /// Signing passwords never travel back to the page, so an empty field means "keep what is saved".
  Map<String, dynamic>? signingPatch() {
    final value = <String, dynamic>{};
    final password = fields['signingPassword']!.text;
    final aliasPassword = fields['signingAliasPassword']!.text;
    if (password.isNotEmpty) value['password'] = password;
    if (aliasPassword.isNotEmpty) value['aliasPassword'] = aliasPassword;
    if (clearSigning) value['clear'] = true;
    return value.isEmpty ? null : value;
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    appActive = WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    for (final controller in fields.values) {
      controller.addListener(changed);
    }
    load();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) pollStatus();
    });
  }

  void changed() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    timer?.cancel();
    jobGeneration++;
    loadToken++;
    WidgetsBinding.instance.removeObserver(this);
    for (final controller in fields.values) {
      controller.dispose();
    }
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    appActive = state == AppLifecycleState.resumed;
    if (!appActive) {
      timer?.cancel();
      timer = null;
    } else {
      pollStatus();
      armPolling();
    }
  }

  bool get foreground =>
      mounted && appActive && ModalRoute.of(context)?.isCurrent == true;

  void armPolling() {
    timer?.cancel();
    timer = null;
    if (!mounted || !appActive || (!running && statusError == null)) return;
    timer = Timer.periodic(const Duration(seconds: 2), (_) => pollStatus());
  }

  void acceptLoaded(dynamic value) {
    if (value is! Map ||
        value['config'] is! Map ||
        value['revision'] is! String ||
        (value['revision'] as String).isEmpty) {
      throw StateError('项目接口未返回有效配置和版本，请重新读取');
    }
    final next = Map<String, dynamic>.from(value);
    final config = Map<String, dynamic>.from(next['config'] as Map);
    final launch = config['launchConfig'] is Map
        ? config['launchConfig'] as Map
        : const {};
    final nextFields = <String, String>{
      for (final name in fields.keys)
        name: signingFields.contains(name)
            ? ''
            : (name == 'splashText' ? launch[name] : config[name])
                    ?.toString() ??
                ''
    };
    nextFields['assets'] = (config['assets'] as List? ?? []).join('\n');
    nextFields['useFeatures'] = (config['useFeatures'] as List? ?? [])
        .where((value) => value != 'continuation')
        .join('\n');
    final rawSigning = config['signingConfig'] is Map
        ? config['signingConfig'] as Map
        : const {};
    nextFields['signingAlias'] = rawSigning['alias']?.toString() ?? '';
    final rawAbis = config['abis'];
    final nextAbis = rawAbis is List
        ? rawAbis.map((item) => item.toString()).toList()
        : List<String>.from(allAbis);
    final permissionConfig = config['permissionConfig'] is Map
        ? config['permissionConfig'] as Map
        : const {};
    final nextPermissions = permissionConfig['manifestPermissions'] is List
        ? (permissionConfig['manifestPermissions'] as List)
            .map((item) => item.toString())
            .toSet()
        : <String>{};
    final rawLevel = config['encryptLevel'];
    final nextLevel =
        rawLevel == null ? 0 : int.tryParse(rawLevel.toString()) ?? -1;
    setState(() {
      loaded = next;
      for (final name in fields.keys) {
        fields[name]!.text = nextFields[name]!;
      }
      encryptLevel = nextLevel;
      abis = nextAbis;
      permissions = nextPermissions;
      startupPermissions =
          (permissionConfig['requestListOnStartup'] as List? ?? [])
              .map((value) => value.toString())
              .toSet();
      features = config['features'] is Map
          ? Map<String, dynamic>.from(config['features'] as Map)
          : {};
      plugins = config['plugins'] is Map
          ? Map<String, dynamic>.from(config['plugins'] as Map)
          : {};
      selectedSigning = {
        for (final key in ['keystore', 'alias', 'uuid'])
          key: rawSigning[key]?.toString() ?? ''
      };
      continuation =
          (config['useFeatures'] as List? ?? []).contains('continuation');
      final rawOptimization = config['optimization'] is Map
          ? config['optimization'] as Map
          : const {};
      optimization = {
        for (final key in optimization.keys) key: rawOptimization[key] == true
      };
      signing = next['signing'] is Map
          ? Map<String, dynamic>.from(next['signing'] as Map)
          : <String, dynamic>{};
      packagedAbis = next['packaging'] is Map
          ? Map<String, dynamic>.from(next['packaging'] as Map)
          : <String, dynamic>{};
      nativeBridge = next['nativeBridge'] is Map
          ? Map<String, dynamic>.from(next['nativeBridge'] as Map)
          : null;
      clearSigning = false;
      displaySplash = launch['displaySplash'] != false;
      hideLogs = launch['hideLogs'] == true;
      stableMode = launch['stableMode'] == true;
      baseline =
          Map<String, dynamic>.from(jsonDecode(jsonEncode(values())) as Map);
      error = null;
    });
  }

  Future<void> load() async {
    final token = ++loadToken;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final value =
          await LocalBridge.call('projectLoad', {'path': widget.path});
      if (!mounted || token != loadToken) return;
      acceptLoaded(value);
    } catch (failure) {
      if (mounted && token == loadToken)
        setState(() => error = failure.toString());
    } finally {
      if (mounted && token == loadToken) setState(() => loading = false);
    }
  }

  Future<void> reload() async {
    if (busy) return;
    if (dirty) {
      final discard = await showDialog<bool>(
          context: context,
          builder: (dialog) => AlertDialog(
                  title: const Text('从磁盘重新读取？'),
                  content: const Text('这会丢弃本页未保存的修改，并读取项目当前配置。'),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(dialog, false),
                        child: const Text('保留草稿')),
                    TextButton(
                        onPressed: () => Navigator.pop(dialog, true),
                        child: const Text('丢弃并重新读取'))
                  ]));
      if (!mounted || discard != true) return;
    }
    await load();
  }

  Map<String, dynamic> patch() {
    final current = values();
    final result = <String, dynamic>{};
    bool same(dynamic left, dynamic right) =>
        jsonEncode(left) == jsonEncode(right);
    const groups = [
      'launchConfig',
      'permissionConfig',
      'features',
      'plugins',
      'optimization',
      'signingConfig'
    ];
    for (final key in current.keys.where((key) => !groups.contains(key))) {
      if (!same(current[key], baseline[key])) {
        result[key] = key == 'versionCode'
            ? int.parse(current[key] as String)
            : current[key];
      }
    }
    for (final group in groups) {
      final currentGroup = current[group] as Map;
      final oldGroup =
          baseline[group] is Map ? baseline[group] as Map : const {};
      final change = <String, dynamic>{};
      for (final key in currentGroup.keys) {
        if (!same(currentGroup[key], oldGroup[key]))
          change[key.toString()] = currentGroup[key];
      }
      for (final key in oldGroup.keys) {
        if (!currentGroup.containsKey(key)) change[key.toString()] = null;
      }
      if (change.isNotEmpty) result[group] = change;
    }
    return result;
  }

  Future<bool> save({bool notify = true}) async {
    if (loading || saving || loaded == null || running) return false;
    if (!(form.currentState?.validate() ?? false)) return false;
    final change = patch();
    final signingChange = signingPatch();
    final version = revision;
    setState(() {
      saving = true;
      error = null;
    });
    try {
      final value = await LocalBridge.call('projectSave', {
        'path': projectPath,
        'revision': version,
        'value': change,
        if (signingChange != null) 'signing': signingChange
      });
      if (!mounted) return false;
      acceptLoaded(value);
      if (notify) message(context, '项目配置已保存');
      return true;
    } catch (failure) {
      if (mounted) setState(() => error = '保存配置失败：$failure');
      return false;
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  void acceptJob(dynamic value) {
    if (value == null) {
      setState(() {
        job = null;
        statusError = null;
      });
      return;
    }
    if (value is! Map ||
        value['id'] == null ||
        !['running', 'success', 'failed'].contains(value['status'])) {
      throw StateError('打包接口未返回有效任务状态');
    }
    final next = Map<String, dynamic>.from(value);
    final expected = projectPath.replaceFirst(RegExp(r'/+$'), '');
    if (next['projectPath']?.toString().replaceFirst(RegExp(r'/+$'), '') !=
        expected) {
      throw StateError('打包状态属于其他项目，已忽略');
    }
    setState(() {
      job = next;
      statusError = null;
    });
  }

  Future<void> pollStatus() async {
    if (!foreground || statusBusy || submitting) return;
    final generation = jobGeneration;
    final id = job?['id'];
    statusBusy = true;
    try {
      final value = await LocalBridge.call('projectBuildStatus',
          {'path': projectPath, if (id != null) 'id': id});
      if (!mounted || generation != jobGeneration) return;
      acceptJob(value);
    } catch (failure) {
      if (mounted && generation == jobGeneration)
        setState(() => statusError = '读取打包状态失败：$failure');
    } finally {
      statusBusy = false;
      if (mounted) armPolling();
    }
  }

  Future<void> buildProject() async {
    if (busy || running || loaded == null) return;
    setState(() {
      submitting = true;
      error = null;
    });
    try {
      if (!await save(notify: false) || !mounted) return;
      jobGeneration++;
      final value = await LocalBridge.call(
          'projectBuild', {'path': projectPath, 'revision': revision});
      if (!mounted) return;
      if (value == null) throw StateError('没有返回打包任务');
      acceptJob(value);
      if (running) message(context, '打包已在本机后台开始，离开页面后仍会继续');
    } catch (failure) {
      if (mounted)
        setState(() {
          error = '启动打包失败：$failure';
          statusError = '提交未确认，可刷新本机任务状态核对';
        });
    } finally {
      if (mounted) {
        setState(() => submitting = false);
        armPolling();
      }
    }
  }

  Future<bool> leave() async {
    if (leaving || busy) {
      message(context, '请等待配置操作完成');
      return false;
    }
    if (!dirty) return true;
    leaving = true;
    try {
      final choice = await showDialog<int>(
          context: context,
          builder: (dialog) => AlertDialog(
                  title: const Text('保存项目配置？'),
                  content: Text(
                      '本页还有未保存的修改。${running ? '\n打包正在后台继续，完成前不能保存配置。离开页面不会停止打包。' : ''}'),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(dialog, 0),
                        child: const Text('继续编辑')),
                    TextButton(
                        onPressed: () => Navigator.pop(dialog, 1),
                        child: const Text('放弃修改')),
                    TextButton(
                        onPressed:
                            running ? null : () => Navigator.pop(dialog, 2),
                        child: const Text('保存'))
                  ]));
      if (!mounted) return false;
      return choice == 1 || (choice == 2 && await save());
    } finally {
      leaving = false;
    }
  }

  Future<void> clearSigningPassword() async {
    if (busy || loaded == null || !hasSavedSigningPassword) return;
    final confirmed = await showDialog<bool>(
        context: context,
        builder: (dialog) => AlertDialog(
                title: const Text('清除已保存的签名密码？'),
                content: const Text('清除后，用项目自定义签名打包会需要重新填写密码。保存配置后生效。'),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(dialog, false),
                      child: const Text('取消')),
                  TextButton(
                      onPressed: () => Navigator.pop(dialog, true),
                      child: const Text('清除'))
                ]));
    if (mounted && confirmed == true) setState(() => clearSigning = true);
  }

  Future<void> chooseMain() async {
    final scripts =
        (loaded?['scripts'] as List? ?? []).whereType<String>().toList();
    if (scripts.isEmpty) {
      message(context, '项目内没有可选择的 JS/MJS 脚本，可填写真实相对路径');
      return;
    }
    final selected = await showDialog<String>(
        context: context,
        builder: (dialog) => AlertDialog(
                title: const Text('选择入口脚本'),
                content: SizedBox(
                    width: 360,
                    height: 320,
                    child: ListView(
                        children: scripts
                            .map((path) => ListTile(
                                title: Text(path),
                                selected: fields['main']!.text == path,
                                onTap: () => Navigator.pop(dialog, path)))
                            .toList())),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(dialog),
                      child: const Text('取消'))
                ]));
    if (mounted && selected != null) fields['main']!.text = selected;
  }

  Future<void> chooseIcon() async {
    if (busy || running) return;
    setState(() => actionBusy = true);
    try {
      final value =
          await LocalBridge.call('importProjectIcon', {'path': projectPath});
      if (mounted &&
          value is Map &&
          value['cancelled'] != true &&
          value['path'] is String)
        fields['icon']!.text = value['path'] as String;
    } catch (failure) {
      if (mounted) setState(() => error = '选择图标失败：$failure');
    } finally {
      if (mounted) setState(() => actionBusy = false);
    }
  }

  Future<Map<String, String>?> credentials(String title,
      {bool create = false}) async {
    final names = create
        ? [
            'name',
            'alias',
            'password',
            'aliasPassword',
            'years',
            'certificateName',
            'organization',
            'unit',
            'country',
            'state',
            'city'
          ]
        : ['password', 'aliasPassword'];
    final controls = {for (final key in names) key: TextEditingController()};
    controls['years']?.text = '25';
    controls['country']?.text = 'CN';
    final labels = {
      'name': '签名文件名',
      'alias': '密钥别名',
      'password': '签名文件密码',
      'aliasPassword': '别名密码（留空沿用文件密码）',
      'years': '有效期（年）',
      'certificateName': '证书姓名',
      'organization': '组织',
      'unit': '部门',
      'country': '国家代码',
      'state': '省份',
      'city': '城市'
    };
    final dialogForm = GlobalKey<FormState>();
    try {
      return await showDialog<Map<String, String>>(
          context: context,
          builder: (dialog) => AlertDialog(
                  title: Text(title),
                  content: SizedBox(
                      width: 360,
                      child: SingleChildScrollView(
                          child: Form(
                              key: dialogForm,
                              child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  children: names
                                      .map((key) => Padding(
                                          padding:
                                              const EdgeInsets.only(bottom: 12),
                                          child: TextFormField(
                                              controller: controls[key],
                                              obscureText:
                                                  key.contains('assword'),
                                              autocorrect: false,
                                              enableSuggestions: false,
                                              keyboardType: key == 'years'
                                                  ? TextInputType.number
                                                  : null,
                                              decoration: InputDecoration(
                                                  labelText: labels[key],
                                                  border:
                                                      const OutlineInputBorder(),
                                                  isDense: true),
                                              validator: (value) {
                                                if ([
                                                      'name',
                                                      'alias',
                                                      'password',
                                                      'certificateName'
                                                    ].contains(key) &&
                                                    (value?.isEmpty ?? true))
                                                  return '请填写${labels[key]}';
                                                if (create &&
                                                    key.contains('assword') &&
                                                    (value?.isNotEmpty ??
                                                        false) &&
                                                    value!.length < 6)
                                                  return '密码至少需要 6 个字符';
                                                if (key == 'years' &&
                                                    ((int.tryParse(value ??
                                                                    '') ??
                                                                0) <
                                                            1 ||
                                                        (int.tryParse(value ??
                                                                    '') ??
                                                                0) >
                                                            100))
                                                  return '请输入 1 到 100 年';
                                                if (key == 'country' &&
                                                    (value?.isNotEmpty ??
                                                        false) &&
                                                    !RegExp(r'^[A-Za-z]{2}$')
                                                        .hasMatch(value!))
                                                  return '例如 CN';
                                                return null;
                                              })))
                                      .toList())))),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(dialog),
                        child: const Text('取消')),
                    TextButton(
                        onPressed: () {
                          if (dialogForm.currentState!.validate())
                            Navigator.pop(dialog, {
                              for (final entry in controls.entries)
                                entry.key: entry.value.text
                            });
                        },
                        child: Text(create ? '创建' : '读取别名'))
                  ]));
    } finally {
      for (final control in controls.values) control.dispose();
    }
  }

  void selectSigning(Map value, String alias,
      {String? password, String? aliasPassword}) {
    setState(() {
      selectedSigning = {
        'keystore': value['path'].toString(),
        'alias': alias,
        'uuid': value['uuid']?.toString() ?? ''
      };
      fields['signingAlias']!.text = alias;
      fields['signingPassword']!.text = password ?? '';
      fields['signingAliasPassword']!.text = aliasPassword ?? '';
      clearSigning = false;
    });
  }

  Future<void> readSigning(Map value) async {
    final secret = await credentials('读取签名文件别名');
    if (!mounted || secret == null) return;
    setState(() => actionBusy = true);
    try {
      final result = await LocalBridge.call('projectSigningAliases', {
        'path': projectPath,
        'keystore': value['path'],
        'password': secret['password']
      });
      if (!mounted) return;
      final aliases = (result as List).map((item) => item.toString()).toList();
      final alias = await showDialog<String>(
          context: context,
          builder: (dialog) => AlertDialog(
                  title: const Text('选择密钥别名'),
                  content: SizedBox(
                      width: 360,
                      child: SingleChildScrollView(
                          child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: aliases
                                  .map((name) => ListTile(
                                      title: Text(name),
                                      onTap: () => Navigator.pop(dialog, name)))
                                  .toList()))),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(dialog),
                        child: const Text('取消'))
                  ]));
      if (mounted && alias != null)
        selectSigning(value, alias,
            password: secret['password'],
            aliasPassword: secret['aliasPassword']);
    } catch (failure) {
      if (mounted) setState(() => error = '读取签名失败：$failure');
    } finally {
      if (mounted) setState(() => actionBusy = false);
    }
  }

  Future<void> importSigning() async {
    if (busy || running) return;
    setState(() => actionBusy = true);
    dynamic result;
    try {
      result =
          await LocalBridge.call('importProjectSigning', {'path': projectPath});
    } catch (failure) {
      if (mounted) setState(() => error = '导入签名失败：$failure');
    } finally {
      if (mounted) setState(() => actionBusy = false);
    }
    if (mounted && result is Map && result['cancelled'] != true)
      await readSigning(result);
  }

  Future<void> chooseSigning() async {
    if (busy || running) return;
    setState(() => actionBusy = true);
    dynamic result;
    try {
      result =
          await LocalBridge.call('projectSigningList', {'path': projectPath});
    } catch (failure) {
      if (mounted) setState(() => error = '读取签名列表失败：$failure');
    } finally {
      if (mounted) setState(() => actionBusy = false);
    }
    if (!mounted || result is! List) return;
    final entries = result.whereType<Map>().toList();
    if (customSignature &&
        !entries.any((entry) => entry['path'] == selectedSigning['keystore']))
      entries
          .insert(0, {'path': selectedSigning['keystore'], 'name': '项目现有签名'});
    if (entries.isEmpty) {
      message(context, '还没有签名文件，请导入或新建');
      return;
    }
    final chosen = await showDialog<Map>(
        context: context,
        builder: (dialog) => AlertDialog(
                title: const Text('选择签名文件'),
                content: SizedBox(
                    width: 360,
                    child: SingleChildScrollView(
                        child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: entries
                                .map((entry) => ListTile(
                                    title: Text(entry['name'].toString()),
                                    subtitle: Text(entry['path'].toString(),
                                        maxLines: 2),
                                    onTap: () => Navigator.pop(dialog, entry)))
                                .toList()))),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(dialog),
                      child: const Text('取消'))
                ]));
    if (mounted && chosen != null) await readSigning(chosen);
  }

  Future<void> createSigning() async {
    if (busy || running) return;
    final values = await credentials('创建 JKS 签名', create: true);
    if (!mounted || values == null) return;
    setState(() => actionBusy = true);
    try {
      final result = await LocalBridge.call('projectSigningCreate', {
        'path': projectPath,
        'name': values['name'],
        'alias': values['alias'],
        'password': values['password'],
        'aliasPassword': values['aliasPassword'],
        'years': int.parse(values['years']!),
        'certificate': {
          'name': values['certificateName'],
          for (final key in [
            'organization',
            'unit',
            'country',
            'state',
            'city'
          ])
            key: values[key]
        }
      });
      if (mounted && result is Map)
        selectSigning(result, result['alias'].toString(),
            password: values['password'],
            aliasPassword: values['aliasPassword']);
    } catch (failure) {
      if (mounted) setState(() => error = '创建签名失败：$failure');
    } finally {
      if (mounted) setState(() => actionBusy = false);
    }
  }

  Future<void> addPlugin() async {
    final choices = (loaded?['installedPlugins'] as List? ?? [])
        .whereType<Map>()
        .where((item) => !plugins.containsKey(item['packageName']))
        .toList();
    if (choices.isEmpty) {
      message(context, '没有可添加的已安装 SDK 插件，请先到插件页安装，再重新读取项目配置');
      return;
    }
    final name = await showDialog<String>(
        context: context,
        builder: (dialog) =>
            AlertDialog(
                title: const Text('添加已安装插件'),
                content: SizedBox(
                    width: 360,
                    child: SingleChildScrollView(
                        child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: choices
                                .map((item) => ListTile(
                                    title: Text(item['name']?.toString() ??
                                        item['packageName'].toString()),
                                    subtitle:
                                        Text(item['packageName'].toString()),
                                    onTap: () => Navigator.pop(dialog,
                                        item['packageName'].toString())))
                                .toList()))),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(dialog),
                      child: const Text('取消'))
                ]));
    if (mounted && name != null)
      setState(() => plugins = {...plugins, name: {}});
  }

  Widget featureChoice(String key, String label, Map<String, String> options) {
    final value = features[key];
    final choice = value == null
        ? '__default'
        : options.containsKey(value)
            ? value.toString()
            : '__custom';
    return Padding(
        padding: const EdgeInsets.only(bottom: 14),
        child: DropdownButtonFormField<String>(
            key: ValueKey('project-feature-$key'),
            value: choice,
            isExpanded: true,
            decoration: InputDecoration(
                labelText: label,
                border: const OutlineInputBorder(),
                isDense: true),
            items: [
              const DropdownMenuItem(
                  value: '__default', child: Text('沿用模板默认设置')),
              ...options.entries.map((entry) =>
                  DropdownMenuItem(value: entry.key, child: Text(entry.value))),
              if (choice == '__custom')
                const DropdownMenuItem(
                    value: '__custom', child: Text('保留项目现有自定义配置'))
            ],
            onChanged: busy
                ? null
                : (next) {
                    if (next != null && next != '__custom')
                      setState(() {
                        features = {...features};
                        if (next == '__default')
                          features.remove(key);
                        else
                          features[key] = next;
                      });
                  }));
  }

  String? relativePath(String? value,
      {bool optional = false, bool script = false}) {
    final text = value ?? '';
    if (text.isEmpty) return optional ? null : '请输入相对路径';
    if (text.startsWith('/') ||
        text.startsWith('\\') ||
        text.contains(':') ||
        text.replaceAll('\\', '/').split('/').contains('..'))
      return '请使用项目内的相对路径';
    if (script && !RegExp(r'\.(js|mjs)$', caseSensitive: false).hasMatch(text))
      return '入口必须是 JS 或 MJS 脚本';
    return null;
  }

  Widget textField(String key, String label,
          {String? helper,
          String? Function(String?)? validator,
          TextInputType? keyboard,
          bool obscure = false,
          bool? enabled,
          int maxLines = 1,
          Widget? suffix}) =>
      Padding(
          padding: const EdgeInsets.only(bottom: 14),
          child: TextFormField(
              key: ValueKey('project-$key'),
              controller: fields[key],
              enabled: enabled ?? !busy,
              keyboardType: keyboard,
              obscureText: obscure,
              maxLines: maxLines,
              autocorrect: false,
              enableSuggestions: false,
              validator: validator,
              decoration: InputDecoration(
                  labelText: label,
                  helperText: helper,
                  helperMaxLines: 3,
                  suffixIcon: suffix,
                  border: const OutlineInputBorder(),
                  isDense: true)));

  Widget card(String title, List<Widget> children) => Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: GlassSurface(
          blur: false,
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(title,
                style:
                    const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
            const SizedBox(height: 14),
            ...children
          ])));

  String stageLabel(String raw) {
    const names = {
      'starting': '准备打包',
      'prepare': '准备项目',
      'copy': '复制文件',
      'copy_project': '复制项目',
      'encrypt': '加密脚本',
      'package': '生成 APK',
      'sign': '签名',
      'complete': '完成'
    };
    final name = names[raw];
    return name == null ? raw : '$name · $raw';
  }

  bool get validArtifact =>
      job?['status'] == 'success' &&
      job?['path'] is String &&
      (job!['path'] as String).startsWith('/') &&
      RegExp(r'^[a-fA-F0-9]{64}$').hasMatch(job?['sha256']?.toString() ?? '') &&
      job?['size'] is num &&
      (job!['size'] as num) > 0;

  Future<void> artifactAction(String method) async {
    if (actionBusy || !validArtifact) return;
    final id = job!['id'];
    setState(() => actionBusy = true);
    try {
      await LocalBridge.call(method, {'id': id});
      if (mounted)
        message(
            context,
            method == 'projectBuildInstall'
                ? '已打开安装页面，请在系统页面确认'
                : '已打开 APK 分享页面');
    } catch (failure) {
      if (mounted) message(context, failure.toString());
    } finally {
      if (mounted) setState(() => actionBusy = false);
    }
  }

  Widget buildStatus() {
    final current = job;
    if (current == null && statusError == null) return const SizedBox.shrink();
    final events = (current?['events'] as List? ?? [])
        .map((entry) => entry.toString())
        .toList();
    final status = current?['status'];
    return card('本地打包', [
      if (statusError != null)
        Text(statusError!,
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
      if (current != null) ...[
        Row(children: [
          if (status == 'running')
            const Padding(
                padding: EdgeInsets.only(right: 10),
                child: SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2)))
          else
            Icon(
                status == 'success'
                    ? Icons.check_circle_outline
                    : Icons.error_outline,
                color: status == 'success'
                    ? Theme.of(context).colorScheme.primary
                    : Theme.of(context).colorScheme.error),
          const SizedBox(width: 8),
          Expanded(
              child: Text(
                  status == 'running'
                      ? '正在打包'
                      : status == 'success'
                          ? 'APK 已生成'
                          : '打包失败',
                  style: const TextStyle(fontSize: 16)))
        ]),
        if ((current['stage']?.toString() ?? '').isNotEmpty)
          Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(stageLabel(current['stage'].toString()),
                  key: const ValueKey('project-build-stage'))),
        if (running)
          const Padding(
              padding: EdgeInsets.only(top: 8),
              child: Text('打包在本机后台执行，离开页面后仍会继续。返回本页可查看结果；当前作业使用启动时的配置。',
                  style: TextStyle(fontSize: 12, height: 1.5))),
        if (status == 'failed')
          Padding(
              padding: const EdgeInsets.only(top: 8),
              child: SelectableText(
                  current['error']?.toString() ?? '打包失败，查看下方日志了解原因')),
        if (status == 'success' && !validArtifact)
          const Padding(
              padding: EdgeInsets.only(top: 8),
              child: Text('返回的 APK 路径或校验信息不完整，请刷新真实状态。')),
        if (validArtifact) ...[
          const SizedBox(height: 10),
          SelectableText(current['path'].toString(),
              style: const TextStyle(fontSize: 12)),
          const SizedBox(height: 6),
          Text(
              '${current['size']} 字节 · ${current['packageName']} · 版本号 ${current['versionCode']} · 加密等级 ${current['encryptLevel']}',
              style: const TextStyle(fontSize: 12)),
          if (current['abis'] is List && (current['abis'] as List).isNotEmpty)
            Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text('本次 APK 架构：${(current['abis'] as List).join('、')}',
                    key: const ValueKey('project-build-abis'),
                    style: const TextStyle(fontSize: 12))),
          const SizedBox(height: 6),
          SelectableText('SHA-256：${current['sha256']}',
              style: const TextStyle(fontSize: 11)),
          Wrap(spacing: 4, children: [
            TextButton.icon(
                onPressed: () async {
                  await Clipboard.setData(
                      ClipboardData(text: current['path'].toString()));
                  if (mounted) message(context, 'APK 路径已复制');
                },
                icon: const Icon(Icons.copy, size: 18),
                label: const Text('复制路径')),
            TextButton.icon(
                onPressed: actionBusy
                    ? null
                    : () => artifactAction('projectBuildShare'),
                icon: const Icon(Icons.share_outlined, size: 18),
                label: const Text('分享 APK')),
            TextButton.icon(
                onPressed: actionBusy
                    ? null
                    : () => artifactAction('projectBuildInstall'),
                icon: const Icon(Icons.install_mobile_outlined, size: 18),
                label: const Text('安装 APK'))
          ])
        ],
        ExpansionTile(
            key: ValueKey('project-events-${current['id']}'),
            tilePadding: EdgeInsets.zero,
            title: Text('打包日志（${events.length}）',
                style: const TextStyle(fontSize: 14)),
            children: [
              Align(
                  alignment: Alignment.centerLeft,
                  child: SelectableText(
                      events.isEmpty ? '当前没有阶段日志' : events.join('\n'),
                      style: const TextStyle(
                          fontFamily: 'monospace',
                          fontFamilyFallback: monoFontFallback,
                          fontSize: 12,
                          height: 1.5)))
            ])
      ],
      Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
              onPressed: statusBusy || submitting ? null : pollStatus,
              icon: const Icon(Icons.refresh, size: 18),
              label: const Text('刷新打包状态')))
    ]);
  }

  Widget configuration() => Form(
      key: form,
      child: Column(children: [
        card('应用信息', [
          Text(
              '${loaded?['engine'] == 'node' ? 'Node.js' : loaded?['engine'] == 'rhino' ? 'Rhino' : loaded?['engine']} 引擎 · ${loaded?['signature'] == 'project' ? '使用项目已有签名' : '使用内置签名'}',
              style: const TextStyle(fontSize: 12)),
          const SizedBox(height: 14),
          textField('name', '应用名称',
              validator: (text) =>
                  (text?.trim().isEmpty ?? true) ? '请输入应用名称' : null),
          textField('packageName', '包名',
              helper: '例如 com.example.myapp',
              validator: (text) =>
                  !RegExp(r'^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')
                          .hasMatch(text ?? '')
                      ? '请输入有效的应用包名'
                      : null),
          textField('versionName', '版本名称',
              validator: (text) =>
                  (text?.trim().isEmpty ?? true) ? '请输入版本名称' : null),
          textField('versionCode', '版本号', keyboard: TextInputType.number,
              validator: (text) {
            final number = int.tryParse(text ?? '');
            return number == null || number <= 0 || number > 2147483647
                ? '请输入有效的正整数版本号'
                : null;
          })
        ]),
        card('脚本和加密', [
          textField('main', '入口脚本',
              helper: '相对于项目目录的 JS/MJS 文件路径',
              validator: (text) => relativePath(text, script: true),
              suffix: IconButton(
                  tooltip: '选择入口脚本',
                  onPressed: busy ? null : chooseMain,
                  icon: const Icon(Icons.folder_open_outlined))),
          DropdownButtonFormField<int>(
              key: const ValueKey('project-encryptLevel'),
              value: [0, 1, 2, 3].contains(encryptLevel) ? encryptLevel : null,
              decoration: const InputDecoration(
                  labelText: '脚本加密等级',
                  border: OutlineInputBorder(),
                  isDense: true),
              items: [0, 1, 2, 3]
                  .map((level) => DropdownMenuItem<int>(
                      value: level,
                      child: Text(const [
                        '不加密',
                        '离线加密',
                        '离线加密 · Dex',
                        '离线加密 · Snapshot'
                      ][level])))
                  .toList(),
              validator: (level) => level == null ? '请选择 0 到 3 的加密等级' : null,
              onChanged: busy
                  ? null
                  : (level) {
                      if (level != null) setState(() => encryptLevel = level);
                    }),
          const SizedBox(height: 8),
          if (![0, 1, 2, 3].contains(encryptLevel))
            Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                    '当前配置的加密等级为 $encryptLevel。本地页面仅支持 0 到 3；请选择支持的等级后再保存。',
                    style: TextStyle(
                        fontSize: 12,
                        color: Theme.of(context).colorScheme.error))),
          const Text('由本机打包引擎处理所选等级，结果以实际日志和生成文件为准。',
              style: TextStyle(fontSize: 12)),
          const SizedBox(height: 14),
          textField('icon', '应用图标',
              helper: '项目内的图片相对路径；留空使用默认图标',
              validator: (text) => relativePath(text, optional: true),
              suffix: IconButton(
                  tooltip: '选择应用图标',
                  onPressed: busy || running ? null : chooseIcon,
                  icon: const Icon(Icons.image_outlined)))
        ]),
        card('运行库与资源', [
          featureChoice('nodejs', 'Node.js 运行库', {
            'auto': '自动按项目决定',
            'enabled': '包含 Node.js 运行库',
            'disabled': '不包含 Node.js 运行库'
          }),
          featureChoice('builtInOCR', '内置 OCR', {
            'disabled': '关闭 OCR',
            'without-models': '包含 OCR 引擎，不带模型',
            'with-default-models': '包含默认模型',
            'with-slim-models': '包含精简模型',
            'with-models': '包含默认和精简模型'
          }),
          CheckboxListTile(
              key: const ValueKey('project-continuation'),
              contentPadding: EdgeInsets.zero,
              title: const Text('Rhino Continuation 支持'),
              subtitle: const Text('供使用 Continuation 的脚本使用'),
              value: continuation,
              onChanged: busy
                  ? null
                  : (value) => setState(() => continuation = value == true)),
          textField('useFeatures', '其他脚本功能',
              helper: '每行一个功能名称，保留项目已有功能', maxLines: 3),
          textField('assets', '打包资源', helper: '每行一个项目内的文件或目录相对路径', maxLines: 3,
              validator: (value) {
            for (final path in lines(value ?? '')) {
              final failure = relativePath(path);
              if (failure != null) return failure;
            }
            return null;
          }),
          Row(children: [
            const Expanded(child: Text('打包插件')),
            TextButton.icon(
                onPressed: busy ? null : addPlugin,
                icon: const Icon(Icons.add, size: 18),
                label: const Text('添加'))
          ]),
          if (plugins.isEmpty)
            const Align(
                alignment: Alignment.centerLeft,
                child: Text('未添加插件', style: TextStyle(fontSize: 12))),
          ...plugins.keys.map((name) => ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(name),
              trailing: IconButton(
                  tooltip: '移除插件 $name',
                  icon: const Icon(Icons.close),
                  onPressed: busy
                      ? null
                      : () => setState(
                          () => plugins = {...plugins}..remove(name))))),
          const Text('插件需要已安装在本机，打包时会包含选中的插件 APK。',
              style: TextStyle(fontSize: 12))
        ]),
        card('打包优化', [
          ...{
            'unusedResources': '移除未使用资源',
            'removeOpenCv': '移除 OpenCV',
            'removeAccessibilityService': '移除无障碍服务',
            'obfuscateComponents': '混淆 Android 组件名'
          }.entries.map((entry) => CheckboxListTile(
              key: ValueKey('project-optimization-${entry.key}'),
              contentPadding: EdgeInsets.zero,
              title: Text(entry.value),
              value: optimization[entry.key] == true,
              onChanged: busy
                  ? null
                  : (value) => setState(() => optimization = {
                        ...optimization,
                        entry.key: value == true
                      }))),
          const Text('移除 OpenCV 后不能使用相关图像功能；移除无障碍服务后不能使用无障碍自动操作。',
              style: TextStyle(fontSize: 12))
        ]),
        card('架构与权限', [
          Text(abiHelper, style: const TextStyle(fontSize: 12)),
          const SizedBox(height: 14),
          DropdownButtonFormField<String>(
              key: const ValueKey('project-abi'),
              value: abiChoice,
              decoration: const InputDecoration(
                  labelText: 'ABI 架构',
                  border: OutlineInputBorder(),
                  isDense: true),
              items: abiLabels.entries
                  .map((entry) => DropdownMenuItem<String>(
                      value: entry.key,
                      enabled: entry.key == 'all' ||
                          abiInfo('shipped').isEmpty ||
                          abiInfo('shipped').contains(entry.key),
                      child: Text(entry.value)))
                  .toList(),
              validator: (choice) => choice == null
                  ? '请选择 ABI 架构'
                  : (choice != 'all' && unavailableAbis.isNotEmpty)
                      ? '本机模板不包含此架构'
                      : null,
              onChanged: busy
                  ? null
                  : (choice) {
                      if (choice != null)
                        setState(() =>
                            abis = List<String>.from(abiChoices[choice]!));
                    }),
          const SizedBox(height: 10),
          if (abiChoice == null)
            Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                    '当前配置的 ABI 为 ${abis.join('、')}。本地页面仅支持 arm64-v8a、armeabi-v7a；请重新选择后再保存。',
                    style: TextStyle(
                        fontSize: 12,
                        color: Theme.of(context).colorScheme.error))),
          if (unavailableAbis.isNotEmpty)
            Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                    '本机运行库不包含 ${unavailableAbis.join('、')}，只打包该架构会没有本地库，请改选已包含的架构。',
                    style: TextStyle(
                        fontSize: 12,
                        color: Theme.of(context).colorScheme.error))),
          Row(children: [
            const Expanded(child: Text('权限', style: TextStyle(fontSize: 16))),
            TextButton(
                onPressed: busy
                    ? null
                    : () => setState(() => permissions =
                        permissionOptions.keys.toSet().union(permissions)),
                child: const Text('全选常用')),
            TextButton(
                onPressed: busy
                    ? null
                    : () => setState(() => permissions = permissions
                        .where((name) => !permissionOptions.containsKey(name))
                        .toSet()),
                child: const Text('清空'))
          ]),
          ...permissionOptions.entries.map((entry) => CheckboxListTile(
              key: ValueKey('project-permission-${entry.key}'),
              contentPadding: EdgeInsets.zero,
              dense: true,
              visualDensity: VisualDensity.compact,
              controlAffinity: ListTileControlAffinity.leading,
              title: Text(entry.value, style: const TextStyle(fontSize: 14)),
              value: permissions.contains(entry.key),
              onChanged: busy
                  ? null
                  : (checked) => setState(() {
                        if (checked == true)
                          permissions.add(entry.key);
                        else {
                          permissions.remove(entry.key);
                          startupPermissions.remove(entry.key);
                        }
                      }))),
          if (extraPermissions.isNotEmpty)
            Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text(
                    '项目已有的 ${extraPermissions.length} 项权限不在列表中，会原样保留：${extraPermissions.join('、')}',
                    style: const TextStyle(fontSize: 12))),
          ExpansionTile(
              tilePadding: EdgeInsets.zero,
              title: Text('启动时申请权限（${startupPermissions.length} 项）'),
              children: [
                ...permissionOptions.entries.map((entry) => CheckboxListTile(
                    key: ValueKey('project-startup-permission-${entry.key}'),
                    contentPadding: EdgeInsets.zero,
                    dense: true,
                    controlAffinity: ListTileControlAffinity.leading,
                    title:
                        Text(entry.value, style: const TextStyle(fontSize: 14)),
                    value: startupPermissions.contains(entry.key),
                    onChanged: busy
                        ? null
                        : (checked) => setState(() {
                              if (checked == true) {
                                startupPermissions.add(entry.key);
                                permissions.add(entry.key);
                              } else {
                                startupPermissions.remove(entry.key);
                              }
                            }))),
                ...startupPermissions
                    .where((name) => !permissionOptions.containsKey(name))
                    .map((name) => CheckboxListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text(name),
                        value: true,
                        onChanged: busy
                            ? null
                            : (_) => setState(
                                () => startupPermissions.remove(name))))
              ]),
          const Text('启动时申请的权限会同时加入应用权限。特殊权限的授权页面由运行引擎处理。',
              style: TextStyle(fontSize: 12))
        ]),
        card('应用签名', [
          Text(
              customSignature
                  ? '签名文件：${selectedSigning['keystore']}'
                  : '使用内置签名',
              style: const TextStyle(fontSize: 12)),
          Wrap(spacing: 4, children: [
            TextButton.icon(
                onPressed: busy || running ? null : chooseSigning,
                icon: const Icon(Icons.folder_open, size: 18),
                label: const Text('选择签名')),
            TextButton.icon(
                onPressed: busy || running ? null : importSigning,
                icon: const Icon(Icons.file_download_outlined, size: 18),
                label: const Text('导入 JKS')),
            TextButton.icon(
                onPressed: busy || running ? null : createSigning,
                icon: const Icon(Icons.add, size: 18),
                label: const Text('新建签名')),
            if (customSignature)
              TextButton(
                  onPressed: busy || running
                      ? null
                      : () => setState(() {
                            selectedSigning = {
                              'keystore': '',
                              'alias': '',
                              'uuid': ''
                            };
                            fields['signingAlias']!.clear();
                            fields['signingPassword']!.clear();
                            fields['signingAliasPassword']!.clear();
                            clearSigning = true;
                          }),
                  child: const Text('使用内置签名'))
          ]),
          const SizedBox(height: 14),
          if (customSignature)
            textField('signingAlias', '密钥别名',
                validator: (value) =>
                    customSignature && (value?.isEmpty ?? true)
                        ? '请选择密钥别名'
                        : null),
          textField('signingPassword', '签名密码',
              obscure: true,
              enabled: customSignature && !busy,
              helper: hasSavedSigningPassword
                  ? '已保存签名密码；这里留空表示沿用已保存的密码'
                  : '使用项目自定义签名打包时必填'),
          textField('signingAliasPassword', '别名密码（可选）',
              obscure: true,
              enabled: customSignature && !busy,
              helper: '留空时与签名密码相同'),
          Row(children: [
            Expanded(
                child: Text(
                    clearSigning
                        ? '已标记清除，保存配置后生效'
                        : hasSavedSigningPassword
                            ? '已保存签名密码（不回显明文）'
                            : '尚未保存签名密码',
                    style: const TextStyle(fontSize: 12))),
            if (hasSavedSigningPassword)
              TextButton(
                  onPressed: busy || clearSigning ? null : clearSigningPassword,
                  child: const Text('清除已保存密码'))
          ]),
          const Text('密码保存在应用私有目录，不写入项目目录里的 project.json，也不会回显到本页；打包时传给本机签名步骤。',
              style: TextStyle(fontSize: 12))
        ]),
        card('启动界面', [
          SwitchListTile(
              key: const ValueKey('project-displaySplash'),
              contentPadding: EdgeInsets.zero,
              title: const Text('显示启动页'),
              value: displaySplash,
              onChanged: busy
                  ? null
                  : (value) => setState(() => displaySplash = value)),
          textField('splashText', '启动页文字'),
          SwitchListTile(
              key: const ValueKey('project-hideLogs'),
              contentPadding: EdgeInsets.zero,
              title: const Text('隐藏运行日志'),
              value: hideLogs,
              onChanged:
                  busy ? null : (value) => setState(() => hideLogs = value)),
          SwitchListTile(
              key: const ValueKey('project-stableMode'),
              contentPadding: EdgeInsets.zero,
              title: const Text('稳定模式'),
              value: stableMode,
              onChanged:
                  busy ? null : (value) => setState(() => stableMode = value))
        ])
      ]));

  @override
  Widget build(BuildContext context) => GlassBackground(
      child: WillPopScope(
          onWillPop: leave,
          child: Scaffold(
              appBar: AppBar(
                  foregroundColor: Theme.of(context).colorScheme.onSurface,
                  systemOverlayStyle: utilitySystemBars(context),
                  leading: BackButton(onPressed: () async {
                    if (await leave() && mounted) Navigator.pop(context);
                  }),
                  title: const Text('项目配置与打包', style: TextStyle(fontSize: 18)),
                  actions: [
                    ActionIcon(
                        tooltip: '重新读取配置',
                        onPressed: busy ? null : reload,
                        icon: const Icon(Icons.refresh))
                  ]),
              body: loaded == null && loading
                  ? const Center(child: CircularProgressIndicator())
                  : ListView(padding: const EdgeInsets.all(16), children: [
                      if (nativeBridge?['detected'] == true)
                        card('第三方 native 库提示', [
                          Text(
                              nativeBridge?['message']?.toString() ??
                                  '检测到第三方 native 库，打包产物可能需 :script 运行时支持。',
                              key: const ValueKey('project-native-bridge'),
                              style: TextStyle(
                                  color: Theme.of(context).colorScheme.error)),
                          const SizedBox(height: 8),
                        ]),
                      SelectableText(projectPath,
                          style: const TextStyle(fontSize: 12)),
                      Padding(
                          padding: const EdgeInsets.only(top: 4, bottom: 12),
                          child: Text(
                              loaded == null
                                  ? '项目配置尚未读取'
                                  : dirty
                                      ? '配置未保存'
                                      : '配置已保存',
                              style: TextStyle(
                                  fontSize: 12,
                                  color: dirty
                                      ? Theme.of(context).colorScheme.primary
                                      : null))),
                      if (error != null)
                        card('操作未完成', [
                          SelectableText(error!,
                              key: const ValueKey('project-error'),
                              style: TextStyle(
                                  color: Theme.of(context).colorScheme.error)),
                          const SizedBox(height: 8),
                          const Text('草稿会保留。若配置已被其他编辑器修改，请先核对，再确认重新读取。',
                              style: TextStyle(fontSize: 12)),
                          Align(
                              alignment: Alignment.centerLeft,
                              child: TextButton(
                                  onPressed: busy ? null : reload,
                                  child: const Text('重新读取配置')))
                        ]),
                      buildStatus(),
                      if (loaded != null) configuration(),
                      if (loading) const LinearProgressIndicator()
                    ]),
              bottomNavigationBar: loaded == null
                  ? null
                  : SafeArea(
                      top: false,
                      child: GlassSurface(
                          radius: 0,
                          blur: false,
                          padding: const EdgeInsets.symmetric(
                              horizontal: 12, vertical: 8),
                          child:
                              Column(mainAxisSize: MainAxisSize.min, children: [
                            if (running)
                              const Padding(
                                  padding: EdgeInsets.only(bottom: 6),
                                  child: Text('正在后台打包，结束后可保存配置或重新打包',
                                      style: TextStyle(fontSize: 12))),
                            Row(children: [
                              Expanded(
                                  child: OutlinedButton(
                                      onPressed:
                                          busy || running ? null : () => save(),
                                      child: Text(saving && !submitting
                                          ? '正在保存…'
                                          : '保存配置'))),
                              const SizedBox(width: 10),
                              Expanded(
                                  child: ElevatedButton(
                                      onPressed:
                                          busy || running ? null : buildProject,
                                      child: Text(submitting
                                          ? '正在提交…'
                                          : job?['status'] == 'failed'
                                              ? '保存并重试'
                                              : '保存并打包')))
                            ])
                          ]))))));
}

module.exports = function (plugin) {
  const helper = require('./nested/helper.js');
  return { add: (a, b) => plugin.add(a, b), nativeAdd: (a, b) => plugin.nativeSum(a, b),
    text: () => plugin.resourceText(), asset: () => plugin.assetText(),
    pluginPackage: () => plugin.packageName(), relativeModule: () => helper.value,
    version: plugin.getVersion(), javaObject: plugin };
};

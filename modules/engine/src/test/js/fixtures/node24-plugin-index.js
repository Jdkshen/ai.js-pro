module.exports = plugin => {
  const sdk = plugin.unwrap();
  if (plugin.getMainScriptPath() !== __filename) throw new Error('Missing real plugin entry path');
  return {javaObject:sdk, add:(a,b)=>sdk.add(a,b), helper:require('./node24-plugin-helper')};
};

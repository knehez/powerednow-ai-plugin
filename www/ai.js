var exec = require('cordova/exec');

/**
 * AI.ask(question, success, error)
 * success: function(string) -> válaszüzenet
 */
exports.ask = function(question, success, error) {
  exec(success, error, 'AIPlugin', 'ask', [question]);
};

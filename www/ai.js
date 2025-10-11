var exec = require('cordova/exec');

/**
 * AI.ask(question, success, error)
 * success: function(string) -> response text
 */
exports.ask = function(question, success, error) {
  exec(success, error, 'AIPlugin', 'ask', [question]);
};

/**
 * AI.transcript(success, error)
 * success: function(string) -> transcribed text
 */
exports.transcript = function(success, error) {
  exec(success, error, 'AIPlugin', 'transcript', []);
};

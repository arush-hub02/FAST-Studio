// Applies the saved theme before the app draws, so the page never flashes the wrong colours.
// A separate file (not inline) so the Content Security Policy can forbid inline scripts.
(function() {
  try {
    var m = localStorage.getItem('fs.theme.mode') || 'dark';
    var a = localStorage.getItem('fs.theme.accent') || 'indigo';
    var d = localStorage.getItem('fs.theme.density') || 'standard';
    document.documentElement.setAttribute('data-theme', m);
    document.documentElement.setAttribute('data-accent', a);
    document.documentElement.setAttribute('data-density', d);
  } catch(e) {}
})();

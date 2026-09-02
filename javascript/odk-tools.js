/* Recherche + tri generiques pour les tableaux des outils internes ODK Tiipaalga.
 * S'applique a tout <div class="table-enhance"> contenant un <input class="table-search-input">
 * (facultatif) et un <table> avec <thead>/<tbody> et des <th data-sortable> (facultatif).
 * Pur JS, aucune dependance - coherent avec le reste de ces outils (pas de framework).
 * Fonctionne uniquement sur les lignes deja presentes dans la page : sur un tableau pagine
 * cote serveur, la recherche ne porte que sur la page actuellement affichee.
 */
(function () {
    'use strict';

    function enhanceTable(wrapper) {
        var table = wrapper.querySelector('table');
        if (!table || !table.tBodies.length) {
            return;
        }
        var tbody = table.tBodies[0];
        var rows = Array.prototype.slice.call(tbody.rows);

        var input = wrapper.querySelector('.table-search-input');
        if (input) {
            input.addEventListener('input', function () {
                var q = input.value.trim().toLowerCase();
                rows.forEach(function (row) {
                    var text = row.textContent.toLowerCase();
                    row.style.display = (q === '' || text.indexOf(q) !== -1) ? '' : 'none';
                });
            });
        }

        if (!table.tHead) {
            return;
        }
        var headers = Array.prototype.slice.call(table.tHead.rows[0].cells);
        headers.forEach(function (th, index) {
            if (!th.hasAttribute('data-sortable')) {
                return;
            }
            th.classList.add('sortable');
            var direction = null;
            th.addEventListener('click', function () {
                direction = direction === 'asc' ? 'desc' : 'asc';
                headers.forEach(function (h) {
                    h.classList.remove('sort-asc', 'sort-desc');
                });
                th.classList.add(direction === 'asc' ? 'sort-asc' : 'sort-desc');

                var sorted = rows.slice().sort(function (a, b) {
                    var av = a.cells[index] ? a.cells[index].textContent.trim() : '';
                    var bv = b.cells[index] ? b.cells[index].textContent.trim() : '';
                    var an = parseFloat(av.replace(',', '.'));
                    var bn = parseFloat(bv.replace(',', '.'));
                    var cmp;
                    if (av !== '' && bv !== '' && !isNaN(an) && !isNaN(bn)) {
                        cmp = an - bn;
                    } else {
                        cmp = av.localeCompare(bv, 'fr', { sensitivity: 'base' });
                    }
                    return direction === 'asc' ? cmp : -cmp;
                });
                sorted.forEach(function (row) {
                    tbody.appendChild(row);
                });
            });
        });
    }

    function setUpProfileMenu() {
        var profile = document.querySelector('.navbar-profile');
        if (!profile) {
            return;
        }
        var trigger = profile.querySelector('.navbar-profile-trigger');
        if (!trigger) {
            return;
        }
        trigger.addEventListener('click', function (e) {
            e.stopPropagation();
            profile.classList.toggle('open');
        });
        document.addEventListener('click', function () {
            profile.classList.remove('open');
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        var wrappers = document.querySelectorAll('.table-enhance');
        for (var i = 0; i < wrappers.length; i++) {
            enhanceTable(wrappers[i]);
        }
        setUpProfileMenu();
    });
})();

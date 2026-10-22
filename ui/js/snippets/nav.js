/**
 * Injects a Connection Health link into the IIQ admin/gear menu.
 */
(function () {
    'use strict';

    function addMenuItem() {
        if (typeof jQuery === 'undefined' || typeof SailPoint === 'undefined') {
            return;
        }

        var href = SailPoint.CONTEXT_PATH
            + '/plugins/pluginPage.jsf?pn=ConnectionHealthDashboard';

        // Avoid duplicate injection on AJAX navigation
        if (jQuery('#chd-nav-link').length > 0) {
            return;
        }

        var link = jQuery('<a>')
            .attr('id', 'chd-nav-link')
            .attr('href', href)
            .attr('role', 'menuitem')
            .text('Connection Health');

        var item = jQuery('<li>').append(link);

        var adminMenu = jQuery(
            '#adminMenuItems, .dropdown-menu[aria-labelledby="adminDropdown"], ul.navbar-right'
        ).first();

        if (adminMenu.length > 0) {
            adminMenu.append(item);
        }
    }

    if (typeof jQuery !== 'undefined') {
        jQuery(document).ready(addMenuItem);
    } else if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', addMenuItem);
    } else {
        addMenuItem();
    }
})();

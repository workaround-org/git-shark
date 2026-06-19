// git-shark hotkeys — progressive enhancement only, the UI works without this.
(function () {
    "use strict";

    var SEQUENCE_TIMEOUT_MS = 1000;
    var pendingKey = null;
    var pendingTimer = null;

    function inFormField(target) {
        if (!target) {
            return false;
        }
        var tag = target.tagName;
        return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable;
    }

    function helpDialog() {
        return document.getElementById("hotkey-help");
    }

    function clearPending() {
        pendingKey = null;
        if (pendingTimer) {
            clearTimeout(pendingTimer);
            pendingTimer = null;
        }
    }

    document.addEventListener("keydown", function (event) {
        if (event.ctrlKey || event.metaKey || event.altKey || inFormField(event.target)) {
            return;
        }

        var dialog = helpDialog();

        if (event.key === "Escape") {
            if (dialog && dialog.open) {
                dialog.close();
                event.preventDefault();
            }
            clearPending();
            return;
        }

        if (pendingKey === "g") {
            clearPending();
            if (event.key === "h") {
                event.preventDefault();
                window.location.href = "/";
            }
            return;
        }

        if (event.key === "g") {
            pendingKey = "g";
            pendingTimer = setTimeout(clearPending, SEQUENCE_TIMEOUT_MS);
            return;
        }

        if (event.key === "?" && dialog) {
            event.preventDefault();
            dialog.showModal();
        }
    });
})();

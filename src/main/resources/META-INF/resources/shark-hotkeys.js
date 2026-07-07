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

    // Generic dialog opener: [data-open-dialog="id"] shows the matching <dialog> as a modal.
    document.addEventListener("click", function (event) {
        var trigger = event.target.closest("[data-open-dialog]");
        if (!trigger) {
            return;
        }
        var target = document.getElementById(trigger.getAttribute("data-open-dialog"));
        if (target && typeof target.showModal === "function") {
            event.preventDefault();
            target.showModal();
        }
    });

    // Copy-to-clipboard: [data-copy="text"] copies its value and briefly confirms on the button.
    document.addEventListener("click", function (event) {
        var button = event.target.closest("[data-copy]");
        if (!button) {
            return;
        }
        event.preventDefault();
        var text = button.getAttribute("data-copy");
        if (!navigator.clipboard || !navigator.clipboard.writeText) {
            return;
        }
        navigator.clipboard.writeText(text).then(function () {
            if (!button.classList.contains("copied")) {
                var original = button.textContent;
                button.classList.add("copied");
                button.textContent = "✓";
                setTimeout(function () {
                    button.textContent = original;
                    button.classList.remove("copied");
                }, 1200);
            }
            showCopyToast(button);
        }).catch(function () {
            // clipboard denied (e.g. insecure context); the command stays visible to copy manually
        });
    });

    // Brief "Copied!" toast. Appended inside the nearest <dialog> (if any) so it renders on the
    // top layer above the modal backdrop; otherwise on <body>.
    function showCopyToast(nearButton) {
        var container = (nearButton.closest && nearButton.closest("dialog")) || document.body;
        var toast = document.createElement("div");
        toast.className = "copy-toast";
        toast.textContent = "Copied!";
        container.appendChild(toast);
        requestAnimationFrame(function () {
            toast.classList.add("show");
        });
        setTimeout(function () {
            toast.classList.remove("show");
            setTimeout(function () {
                toast.remove();
            }, 200);
        }, 1200);
    }
})();

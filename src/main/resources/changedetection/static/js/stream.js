// The data layer the pages use to be told about changes.
//
// It presents the same three calls the page's own script already made -- on, emit,
// disconnect -- over a server-sent event stream rather than a socket. Everything above this
// file is unchanged: the event names, the payload shapes and the handling of both.
//
// Reconnection is the browser's own for the stream and explicit for the state that follows it:
// EventSource retries on its own, and the server sends current counts as the first thing on
// every connection, so a page that was disconnected converges rather than waiting for the next
// change.
function cdioStream(streamPath) {
    const handlers = {};
    let source = null;
    let closedByUs = false;

    function fire(name, payload) {
        (handlers[name] || []).forEach(function (handler) {
            try {
                handler(payload);
            } catch (e) {
                console.error('stream handler for ' + name + ' failed:', e);
            }
        });
    }

    function attach(name) {
        source.addEventListener(name, function (message) {
            let payload = {};
            try {
                payload = JSON.parse(message.data);
            } catch (e) {
                payload = {};
            }
            fire(name, payload);
        });
    }

    const serverEvents = [
        'queue_size', 'checking_now', 'operation_result', 'toast',
        'watch_small_status_comment', 'notification_event', 'watch_deleted',
        'watch_bumped_favicon', 'general_stats_update', 'watch_update', 'keepalive'
    ];

    function open() {
        source = new EventSource(streamPath);
        serverEvents.forEach(attach);

        source.onopen = function () {
            fire('connect');
        };
        source.onerror = function (error) {
            if (closedByUs) {
                return;
            }
            // EventSource reconnects by itself; a page that has been told it is disconnected
            // has to be told again when it is not, which is what onopen above does.
            fire('disconnect', 'transport error');
            fire('connect_error', error);
        };
    }

    return {
        on: function (name, handler) {
            (handlers[name] = handlers[name] || []).push(handler);
        },
        emit: function (name, payload) {
            const path = name === 'watch_operation'
                ? streamPath + '/watch-operation'
                : streamPath + '/checkbox-operation';
            fetch(path, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'x-csrftoken': typeof csrftoken !== 'undefined' ? csrftoken : ''
                },
                body: JSON.stringify(payload || {})
            }).catch(function (error) {
                console.error('stream operation failed:', error);
            });
        },
        disconnect: function () {
            closedByUs = true;
            if (source) {
                source.close();
            }
        },
        open: open
    };
}

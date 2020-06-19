(ns server.auth.data)

(defonce unique-session-id (atom 0))
(defonce alloc-auth-permissions (atom nil))
(defonce alloc-auth-users (atom nil))
(defonce alloc-auth-logged-in-users (atom {}))



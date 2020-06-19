(ns web.auth.utils)

(defn logged-in-user-description
      [logged-in-user]
      (if logged-in-user
        (get-in logged-in-user [:user])
        "Anonymous"))

(defn is-logged-in?
      [logged-in-user]
      (and logged-in-user
           (:user logged-in-user)
           (contains?
             #{:google :local}
             (:authority logged-in-user))))

(defn same-user?
  [get-logged-in-user-response db-logged-in-user]
  (map
    (fn[u-map]
      (select-keys
        u-map
        [:user :authority :client-id :expires]))
    [get-logged-in-user-response db-logged-in-user])
  (apply
    =
    (map
      (fn[u-map]
        (select-keys
          u-map
          [:user :authority :client-id :expires]))
      [get-logged-in-user-response db-logged-in-user])))

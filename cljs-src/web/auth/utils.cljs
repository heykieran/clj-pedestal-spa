(ns web.auth.utils)

(defn logged-in-user-description
      [logged-in-user]
      (if logged-in-user
        (str (get-in logged-in-user [:user])
             "/"
             (get-in logged-in-user [:authority])
             (if (get-in logged-in-user [:ext-token])
               "*"))
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
        [:user :authority :client-id :expires :ext-token]))
    [get-logged-in-user-response db-logged-in-user])
  (apply
    =
    (map
      (fn[u-map]
        (select-keys
          u-map
          [:user :authority :client-id :expires :ext-token]))
      [get-logged-in-user-response db-logged-in-user])))

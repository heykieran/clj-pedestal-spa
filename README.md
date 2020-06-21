# About

In this version of the repository I demonstrate how to add a jwt authentication backend to the existing session based authentication in the application. 

For the final version of the application I'm planning to introduce a Google IAP (Identity Aware Proxy) between the internet and the application. Therefore, I'll implement the JWT backend a little differently than how buddy-auth's supplied JWT backend works. I want to be able to use _any_ HTTP request header rather than having to use a JWT token enbedded in the `authorization` header.

A more complete discussion of all this can be found in this [blog post][related-blog-post].

[related-blog-post]: https://heykieran.github.io/post/adding-a-custom-auth-backend/

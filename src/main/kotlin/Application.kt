/**
 * Created by natsuki on 07/09/2017.
 */

fun main(args: Array<String>) {

    Server(1234).apply {
        static("/static")
        post("form") { req, res ->
            res.send(req.content, Status.Created)
        }
        put("form") { req, res ->
            res.send(req.content, Status.Created)
        }
        delete("order") { req, res ->
            res.send(req.content, Status.Created)
        }
        get("/hello") { _, res ->
            res.send(" hello ")
        }
        get("/404") { _, _ ->
            throw IllegalArgumentException(" AAA ")
        }
        get("/form/id/:id") { _, res ->
            res.send(" hello world ")
        }
        before { req, _ ->
            println(" ==== before processing request ==== ")
            println(" url : ${req.url}    query : ${req.query}")
        }
        after { _, _ ->
            println(" ==== after processing request ====")
        }
        exception(IllegalStateException::class) { _, _ ->
            "IllegalState"
        }
    }.start()
}
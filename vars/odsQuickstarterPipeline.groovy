import org.ods.quickstarter.Pipeline
import org.ods.services.ServiceRegistry

def call(Map config, Closure body) {
    def pipeline = new Pipeline(this, config)
    pipeline.execute(body)

}

return this

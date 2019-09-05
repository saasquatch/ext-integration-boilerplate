package saasquatch.extintegration;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class EIGraphQLResponse {

  private ObjectNode data;
  private ArrayNode errors;

  @Deprecated EIGraphQLResponse() {}

  public EIGraphQLResponse(ObjectNode data, ArrayNode errors) {
    this.data = data;
    this.errors = errors;
  }

  public ObjectNode getData() {
    return data;
  }

  public void setData(ObjectNode data) {
    this.data = data;
  }

  public ArrayNode getErrors() {
    return errors;
  }

  public void setErrors(ArrayNode errors) {
    this.errors = errors;
  }

}

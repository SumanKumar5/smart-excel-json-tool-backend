package com.example.backendapp.model;

public class ResponseModel<T> {
    private T data;

    public ResponseModel() {}

    public ResponseModel(T data) {
        this.data = data;
    }

    public T getData() {
        return data;
    }
    public void setData(T data) {
        this.data = data;
    }
}

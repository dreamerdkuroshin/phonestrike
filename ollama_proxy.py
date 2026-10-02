#!/usr/bin/env python3
from flask import Flask, request, jsonify
import requests
import json

app = Flask(__name__)
LLAMA_URL = "http://127.0.0.1:11434"

@app.route('/api/generate', methods=['POST'])
def generate():
    data = request.json
    prompt = data.get('prompt', '')
    model = data.get('model', 'Phi-3-mini-4k-instruct-Q4_K_M')
    stream = data.get('stream', False)

    payload = {
        "model": model,
        "prompt": prompt,
        "max_tokens": 150,
        "temperature": 0.7,
        "stream": stream
    }
    
    try:
        resp = requests.post(f"{LLAMA_URL}/v1/completions", json=payload)
        if resp.status_code == 200:
            result = resp.json()
            text = result.get("choices", [{}])[0].get("text", "")
            return jsonify({"response": text})
        else:
            return jsonify({"error": f"LLM server error: {resp.status_code}"}), 500
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/tags', methods=['GET'])
def tags():
    return jsonify({
        "models": [
            {"name": "Phi-3-mini-4k-instruct-Q4_K_M"}
        ]
    })

if __name__ == '__main__':
    app.run(host='127.0.0.1', port=11435)

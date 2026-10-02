#!/data/data/com.termux/files/usr/bin/python3
import json
import os
from datetime import datetime

HISTORY_FILE = os.path.expanduser("~/jarvis-chat-history.json")

class ChatHistory:
    def __init__(self):
        self.history = self.load()
    
    def load(self):
        if os.path.exists(HISTORY_FILE):
            with open(HISTORY_FILE, 'r') as f:
                return json.load(f)
        return {"messages": []}
    
    def save(self):
        with open(HISTORY_FILE, 'w') as f:
            json.dump(self.history, f, indent=2)
    
    def add(self, user, assistant):
        self.history["messages"].append({
            "user": user,
            "assistant": assistant,
            "timestamp": datetime.now().isoformat()
        })
        self.save()
    
    def get_recent(self, n=10):
        return self.history["messages"][-n:]
    
    def search(self, query):
        results = []
        for msg in self.history["messages"]:
            if query.lower() in msg["user"].lower() or query.lower() in msg["assistant"].lower():
                results.append(msg)
        return results

if __name__ == "__main__":
    history = ChatHistory()
    print(f"📋 Chat History: {len(history.history['messages'])} messages")

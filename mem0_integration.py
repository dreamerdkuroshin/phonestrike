#!/data/data/com.termux/files/usr/bin/python3
from mem0 import Memory
import json
import os

MEMORY_FILE = os.path.expanduser("~/jarvis-memory.json")

class JarvisMemory:
    def __init__(self):
        self.memory = Memory()
        self.user_id = "azhri1990"
        self.load_local()
    
    def load_local(self):
        if os.path.exists(MEMORY_FILE):
            with open(MEMORY_FILE, 'r') as f:
                self.local_memory = json.load(f)
        else:
            self.local_memory = {}
    
    def save_local(self):
        with open(MEMORY_FILE, 'w') as f:
            json.dump(self.local_memory, f)
    
    def add(self, content, metadata=None):
        result = self.memory.add(content, user_id=self.user_id, metadata=metadata)
        self.local_memory[content] = metadata
        self.save_local()
        return result
    
    def search(self, query):
        return self.memory.search(query, user_id=self.user_id)
    
    def get_all(self):
        return self.local_memory

if __name__ == "__main__":
    jarvis_memory = JarvisMemory()
    print("🧠 Jarvis Memory System Ready!")

package nachos.threads;
import java.io.*;

public class Hashtable<K,V>
    extends Dictionary<K,V>
	implements Map<K,V>, Cloneable, java.io.Serializable {

	private transient Entry[] table;
	private transient int count;
	private int threshold;
	int lock=0;
	private transient int modCount = 0;

	
	
	
	public HashTable(int n_buckets) {
        if (n_buckets < 0)  
            throw new IllegalArgumentException("Illegal Number: "+  
                                               n_buckets);  
        if (n_buckets==0)  
            n_buckets = 1;
        table = new Entry[n_buckets];  
        threshold = (int)Math.min(n_buckets, MAX_ARRAY_SIZE + 1); 
    } 

	
	public insert(int key, int val) {  
        // Make sure the value is not null  
        if (value == null) { 
            throw new NullPointerException();  
        }  
        // Makes sure the key is not already in the hashtable.  
        Entry tab[] = table;  
        int hash = hash(key);  
        int index = (hash & 0x7FFFFFFF) % tab.length;  
        for (Entry<K,V> e = tab[index] ; e != null ; e = e.next) {  
            if ((e.hash == hash) && e.key.equals(key) && lock == 0) {  
                V old = e.value;  
                e.value = value;  
                return old;  
            }  
        }  
        modCount++;  
        if (count >= threshold) {  
            // Rehash the table if the threshold is exceeded  
			throw new IllegalArgumentException("bucket is full"); 

        }  

        return null;  
    }
	
	
	public  remove(int key) {
			Entry tab[] = table;
			int hash = key;
			int index = (hash & 0x7FFFFFFF) % tab.length;
			
			for (Entry<K,V> e = tab[index], prev = null ; e != null ; prev = e, e = e.next) {
				if ((e.hash == hash) && lock == 0) {
					modCount++;
					if (prev != null) {
						prev.next = e.next;
					} else {
						tab[index] = e.next;
					}
					count--;
					V oldValue = e.value;
					e.value = null;
					return oldValue;
				}
			}
			return null;
		}

		
	public get(int key) {  
		Entry tab[] = table;  
		int hash = key;  
		int index = (hash & 0x7FFFFFFF) % tab.length; 
		for (Entry<K,V> e = tab[index] ; e != null ; e = e.next) {  
			if ((e.hash == hash) && lock == 0) {  
				return e.value;  
			}  
		}  
		return null;  
	}

	
	public getbucketsize(){
		if(lock == 0){
			return count;
		}
		return null;
	}
	
	public batch(int n ops, ThreadOperation [] ops){
		
	}
	




}
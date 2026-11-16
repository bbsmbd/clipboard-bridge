//! UI strings: Bengali (default) and English.

pub fn tr(lang: &str, key: &str) -> &'static str {
    let bn = lang != "en";
    match key {
        "title" => "Clipboard Bridge V1",
        "connected" => if bn { "সংযুক্ত" } else { "Connected" },
        "waiting" => if bn { "ফোনের অপেক্ষায়..." } else { "Waiting for phone..." },
        "paused" => if bn { "বন্ধ রাখা হয়েছে" } else { "Paused" },
        "phones_on" => if bn { "সংযুক্ত ফোন: " } else { "Connected phone: " },
        "phones_known" => if bn { "যুক্ত ফোন: " } else { "Paired phone: " },
        "no_phone" => if bn { "কোনো ফোন যুক্ত নেই — ফোন অ্যাপে নিচের পিন দিন" } else { "No phone paired — enter the PIN below in the phone app" },
        "ip" => if bn { "এই পিসির IP: " } else { "This PC IP: " },
        "port" => if bn { "পোর্ট" } else { "port" },
        "pin_title" => if bn { "পেয়ার পিন (ফোনে লিখুন):" } else { "Pairing PIN (type on phone):" },
        "new_pin" => if bn { "নতুন পিন" } else { "New PIN" },
        "pause" => if bn { "সিঙ্ক বন্ধ" } else { "Pause sync" },
        "resume" => if bn { "সিঙ্ক চালু" } else { "Resume sync" },
        "unpair" => if bn { "সব ফোন মুছুন" } else { "Unpair all" },
        "lang" => "English / বাংলা",
        "tip" => if bn {
            "ফোন আর পিসি একই WiFi/রাউটারে রাখুন। পিসিতে Ctrl+C করলে ফোনে সাথে সাথে চলে যায়, ফোনে কপি করলে পিসিতে Ctrl+V দিন। উইন্ডো বন্ধ করলেও অ্যাপ ট্রেতে চলতে থাকে।"
        } else {
            "Keep phone and PC on the same WiFi/router. Ctrl+C on PC goes to the phone instantly; copy on phone, then Ctrl+V on PC. Closing the window keeps the app running in the tray."
        },
        "tray_show" => if bn { "খুলুন" } else { "Open" },
        "tray_quit" => if bn { "সম্পূর্ণ বন্ধ করুন" } else { "Quit" },
        "tray_hint" => if bn { "Clipboard Bridge ট্রেতে চলছে। আইকনে ডাবল-ক্লিক করে খুলুন।" } else { "Clipboard Bridge keeps running in the tray. Double-click the icon to open." },
        "sent" => if bn { "ফোনে পাঠানো হলো: " } else { "Sent to phone: " },
        "recv" => if bn { "ফোন থেকে এলো (Ctrl+V দিন): " } else { "From phone (press Ctrl+V): " },
        "copied_nophone" => if bn { "কপি হয়েছে, কিন্তু কোনো ফোন সংযুক্ত নেই" } else { "Copied, but no phone is connected" },
        "too_big" => if bn { "লেখা অনেক বড় (১০ লাখ অক্ষরের বেশি) — পাঠানো হয়নি" } else { "Text too large (over 1,000,000 chars) — not sent" },
        "img_sent" => if bn { "ছবি ফোনে পাঠানো হলো: " } else { "Image sent to phone: " },
        "img_recv" => if bn { "ফোন থেকে ছবি এলো (Ctrl+V দিন): " } else { "Image from phone (press Ctrl+V): " },
        "img_too_big" => if bn { "ছবি অনেক বড় (২০ MB-এর বেশি) — পাঠানো হয়নি" } else { "Image too large (over 20 MB) — not sent" },
        "img_bad" => if bn { "ফোনের ছবিটা খোলা যায়নি" } else { "Could not open the image from the phone" },
        "paired" => if bn { "নতুন ফোন যুক্ত হলো: " } else { "New phone paired: " },
        "new_pin_log" => if bn { "নতুন পিন: " } else { "New PIN: " },
        "unpair_confirm" => if bn { "সব যুক্ত ফোন মুছে ফেলবেন? ফোনে আবার পিন দিয়ে যুক্ত করতে হবে।" } else { "Remove all paired phones? They must pair again with the PIN." },
        "port_busy" => if bn {
            "পোর্ট 54546 অন্য একটি প্রোগ্রাম ধরে রেখেছে (সম্ভবত পুরোনো Clipboard Bridge চলছে)।\n\nTask Manager খুলে clipboard_bridge_pc / python বন্ধ করে আবার চালান।"
        } else {
            "Port 54546 is used by another program (probably the old Clipboard Bridge).\n\nClose clipboard_bridge_pc / python in Task Manager and start again."
        },
        "fw_ok" => if bn { "ফায়ারওয়াল নিয়ম ঠিক আছে" } else { "Firewall rules OK" },
        "fw_added" => if bn { "ফায়ারওয়াল নিয়ম যোগ হলো" } else { "Firewall rules added" },
        "fw_fail" => if bn { "ফায়ারওয়াল নিয়ম যোগ হয়নি — অনুমতি (Yes) দিন, নইলে ফোন পিসিতে পৌঁছাতে পারবে না" } else { "Firewall rules not added — allow the prompt (Yes), otherwise the phone cannot reach the PC" },
        "ready" => if bn { "প্রস্তুত। পিন: " } else { "Ready. PIN: " },
        _ => "",
    }
}

const express = require("express");
const mongoose = require("mongoose");
const app = express();
const PORT = 3000;

app.use(express.json());

mongoose.connect("mongodb://mongo:27017/mydb", {
  useNewUrlParser: true,
  useUnifiedTopology: true,
});

const User = mongoose.model("User", new mongoose.Schema({ name: String }));

app.post("/users", async (req, res) => {
  const user = new User({ name: req.body.name });
  await user.save();
  res.send(user);
});

app.get("/users", async (req, res) => {
  const users = await User.find();
  res.send(users);
});

app.listen(PORT, () => {
  console.log(`App running on http://localhost:${PORT}`);
});

const express = require('express');
const app = express();
const routes = require('./routes')
const sequelize = require('./config/connection')

const PORT = process.env.PORT || 3000;

app.use(express.static('public'));

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

app.use(routes)

sequelize.sync({ force: true }).then(() => {
    app.listen(PORT, () => console.log(`App is listening on port ${PORT}`));
});
const Income = require('./Income');
const Bills = require('./Bills');
const User = require('./User');

User.hasMany(Bills, {
    foreignKey: 'user_id'
});

User.hasMany(Income, {
    foreignKey: 'user_id'
});

Income.belongsTo(User, {
    foreignKey: 'user_id'
});

Bills.belongsTo(User, {
    foreignKey: 'user_id'
})

module.exports = {
    Income,
    Bills,
    User
}